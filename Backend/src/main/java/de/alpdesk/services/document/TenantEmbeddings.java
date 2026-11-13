package de.alpdesk.services.document;

import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.services.ai.AiModelService;
import de.alpdesk.services.tenant.TenantDatabases;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * The table "embedding" of a tenant database: langchain4j's PgVectorEmbeddingStore, created with the vector length of
 * the tenant's embedding model. Every chunk carries the metadata {@link #DOCUMENT_ID} and {@link #FILE_NAME}.
 * <p>
 * Both searches are SQL of our own and join each chunk to its document: who finds a chunk is decided by the document's
 * area ({@link KnowledgeScope}), so moving a document to another area changes no chunk, and only a READY document's
 * chunks are found - not one deleted or replaced meanwhile.
 */
@Singleton
public class TenantEmbeddings {

    public static final String DOCUMENT_ID = "document_id";
    public static final String FILE_NAME = "file_name";
    private static final String TABLE = "embedding";
    private static final String UNDEFINED_TABLE = "42P01";
    /**
     * Every chunk with its document, only while the document is READY: a chunk whose document was deleted meanwhile
     * drops out, and so do the previous version's chunks of a document replaced and waiting to be embedded again - they
     * may belong to another area than the document now does.
     */
    private static final String JOIN_DOCUMENT = "JOIN document d ON d.id = (e.metadata->>'" + DOCUMENT_ID + "')::bigint "
            + "AND d.status = 'READY'";
    /**
     * A chunk's document as the deletes ask for it - ours and langchain4j's removeAll, whose filter on a Long writes
     * exactly this. The index on it ({@link #DOCUMENT_INDEX}) must use the same expression to be hit.
     */
    private static final String CHUNK_DOCUMENT = "(metadata->>'" + DOCUMENT_ID + "')::bigint";
    private static final String DOCUMENT_INDEX = "embedding_document_idx";
    /** The full-text index's name starts with it - one index of this kind per table, for the tenant's language. */
    private static final String TEXT_INDEX_PREFIX = "embedding_text_";
    /**
     * How long an index build waits for the transactions it must let finish - the connections' 10 s are for a lock on a
     * row; CONCURRENTLY waits for every transaction holding the table or a snapshot, and the application's own end
     * after a minute idle at the latest (idle_in_transaction_session_timeout).
     */
    static final Duration INDEX_LOCK_TIMEOUT = Duration.ofMinutes(2);

    private static final Logger LOG = LoggerFactory.getLogger(TenantEmbeddings.class);

    private final TenantDatabases databases;
    /** The databases whose table has its indexes - created once per table, again after a drop. */
    private final Set<String> indexed = ConcurrentHashMap.newKeySet();

    public TenantEmbeddings(TenantDatabases databases) {
        this.databases = databases;
    }

    /**
     * The store the indexer writes into: its table is created on first use with the vector length of the tenant's
     * embedding model (the tenant migrations created the extension), with its indexes: the full-text index, the index
     * on a chunk's document for removing its chunks, and an HNSW index for the vector search. Without the HNSW index
     * every chunk is compared with the query - fine for a few thousand chunks, too slow for a tenant with tens of
     * thousands; with it the search stays in milliseconds, approximate but at our numbers of results practically
     * exact. pgvector's HNSW takes at most 2000 dimensions (AiModelService.MAX_DIMENSION); a model entered with more
     * before that limit is searched without.
     * <p>
     * Only the indexer calls it, under the tenant's lock (DocumentIndexer.withTenantLock), which a change of the
     * embedding model takes too: two CREATE TABLE IF NOT EXISTS at the same moment fail on PostgreSQL's catalog
     * (23505), and a search still holding the previous model would create the table with that model's vector length -
     * every later document would then fail. Searching and removing never create it.
     */
    public EmbeddingStore<TextSegment> storeForIndexing(String databaseName, int dimension, SearchLanguage language) {
        EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(databases.dataSource(databaseName))
                .table(TABLE)
                .dimension(dimension)
                .createTable(true)
                .skipCreateVectorExtension(true)
                .build();
        if (!indexed.contains(databaseName)) {
            createIndexes(databaseName, dimension, language);
            indexed.add(databaseName);
        }
        return store;
    }

    /**
     * The chunks closest to the query's vector with a score of at least minScore, closest first, of the documents the
     * scope finds; none while no document was embedded yet (no table). The score is langchain4j's: (cosine + 1) / 2.
     * <p>
     * The HNSW index finds the closest chunks first; the area and the document's status are checked afterwards: with
     * only its usual 40 candidates, a user of a small area would get fewer hits than asked for. pgvector's iterative
     * scan (hnsw.iterative_scan, pgvector 0.8 - set for this query only) goes on through the index until enough chunks
     * pass, but at most hnsw.max_scan_tuples of them (20,000 by default): in a tenant with far more chunks, a small
     * area whose chunks are all further from the question can still get fewer hits. Not raised before a measurement
     * shows it (docs/architecture/ai-features.md "Knowledge areas").
     */
    public List<ChunkMatch> searchVectors(String databaseName, float[] vector, double minScore, int limit,
                                          KnowledgeScope scope) {
        String sql = "SELECT e.embedding_id::text AS id, d.id AS document_id, d.chunk_count, e.text, e.metadata->>'" + FILE_NAME + "' AS file_name, "
                + "(2 - (e.embedding <=> CAST(? AS vector))) / 2 AS score FROM " + TABLE + " e " + JOIN_DOCUMENT
                + scopeCondition("WHERE", scope) + " ORDER BY e.embedding <=> CAST(? AS vector) LIMIT ?";
        String literal = vectorLiteral(vector);
        try (Connection connection = databases.dataSource(databaseName).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL hnsw.iterative_scan = strict_order");
                }
                List<ChunkMatch> matches = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    statement.setString(index++, literal);
                    index = setScope(connection, statement, index, scope);
                    statement.setString(index++, literal);
                    statement.setInt(index, limit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            double score = result.getDouble("score");
                            if (score >= minScore) {
                                matches.add(new ChunkMatch(result.getString("id"), result.getLong("document_id"), result.getInt("chunk_count"),
                                        result.getString("file_name"), result.getString("text"), score));
                            }
                        }
                    }
                }
                connection.commit();
                return matches;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (missingTable(e)) {
                return List.of();
            }
            throw new IllegalStateException("Could not search the vectors of " + databaseName, e);
        }
    }

    /** The ids of the READY documents the scope finds - to filter what langchain4j's own search found. */
    public Set<Long> documentIdsFound(String databaseName, KnowledgeScope scope) {
        String sql = "SELECT d.id FROM document d WHERE d.status = 'READY'" + scopeCondition("AND", scope);
        try (Connection connection = databases.dataSource(databaseName).getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setScope(connection, statement, 1, scope);
            Set<Long> ids = new HashSet<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the documents of " + databaseName, e);
        }
    }

    /**
     * langchain4j's own hybrid search on the same table (all words of the query must match) - for the retrieval
     * measurement only, which compares it with ours ({@link KnowledgeSearch}).
     */
    public EmbeddingStore<TextSegment> langchain4jHybridStore(String databaseName, int dimension,
                                                              SearchLanguage language) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(databases.dataSource(databaseName))
                .table(TABLE)
                .dimension(dimension)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .searchMode(PgVectorEmbeddingStore.SearchMode.HYBRID)
                .textSearchConfig(language.config())
                .rrfK(60)
                .build();
    }

    /**
     * The chunks containing any word of the query (stems and stop words of the tenant's language), the most matching
     * first, of the documents the scope finds; none while no document was embedded yet (no table). Any word, not all: a
     * question in a sentence ("Was bedeutet E23?") still finds the chunk with the code, the chunks matching more words
     * come first.
     */
    public List<ChunkMatch> searchText(String databaseName, SearchLanguage language, String query, int limit,
                                       KnowledgeScope scope) {
        String sql = textSearchSql(language, scope);
        try (Connection connection = databases.dataSource(databaseName).getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            int index = setScope(connection, statement, 2, scope);
            statement.setInt(index, limit);
            List<ChunkMatch> matches = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    matches.add(new ChunkMatch(result.getString("id"), result.getLong("document_id"), result.getInt("chunk_count"),
                            result.getString("file_name"), result.getString("text"), 0));
                }
            }
            return matches;
        } catch (SQLException e) {
            if (missingTable(e)) {
                return List.of();
            }
            throw new IllegalStateException("Could not search the texts of " + databaseName, e);
        }
    }

    /** Removes the document's chunks - nothing to do while no document was embedded yet (no table). */
    public void removeDocument(String databaseName, int dimension, Long documentId) {
        try {
            existingStore(databaseName, dimension).removeAll(metadataKey(DOCUMENT_ID).isEqualTo(documentId));
        } catch (RuntimeException e) {
            if (!missingTable(e)) {
                throw e;
            }
        }
    }

    /** Removes the chunks of these documents (deleted together) - nothing to do while there is no table. */
    public void removeDocuments(String databaseName, List<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        String sql = "DELETE FROM " + TABLE + " WHERE " + CHUNK_DOCUMENT + " = ANY(?)";
        try (Connection connection = databases.dataSource(databaseName).getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("bigint", documentIds.toArray()));
            statement.executeUpdate();
        } catch (SQLException e) {
            if (!missingTable(e)) {
                throw new IllegalStateException("Could not remove the chunks of " + databaseName, e);
            }
        }
    }

    /** Drops the table - its vector length belongs to the previous embedding model. */
    public void drop(String databaseName) {
        execute(databaseName, "DROP TABLE IF EXISTS " + TABLE);
        indexed.remove(databaseName);
    }

    /** The store on a table that may not exist yet - it is created by {@link #storeForIndexing} only. */
    private EmbeddingStore<TextSegment> existingStore(String databaseName, int dimension) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(databases.dataSource(databaseName))
                .table(TABLE)
                .dimension(dimension)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .build();
    }

    private static boolean missingTable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNDEFINED_TABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** The full-text search's statement (the query and the limit as parameters) - the specs EXPLAIN exactly it. */
    static String textSearchSql(SearchLanguage language, KnowledgeScope scope) {
        String chunkText = textVector(language, "e.text");
        return "SELECT e.embedding_id::text AS id, d.id AS document_id, d.chunk_count, e.text, e.metadata->>'" + FILE_NAME + "' AS file_name, "
                + "ts_rank_cd(" + chunkText + ", query) AS rank FROM " + TABLE + " e " + JOIN_DOCUMENT + ", "
                + "CAST(replace(plainto_tsquery('" + language.config() + "', ?)::text, ' & ', ' | ') AS tsquery) query "
                + "WHERE " + chunkText + " @@ query" + scopeCondition("AND", scope) + " ORDER BY rank DESC LIMIT ?";
    }

    /**
     * A chunk's text as the full-text search reads it, in the tenant's language: its stemming and stop words. The index
     * on exactly this expression makes the search fast - a query must use the same expression to hit it.
     * <p>
     * '<' and '>' become blanks first: PostgreSQL's parser takes everything between them for an HTML tag and drops it
     * with its attributes - a chunk of XML configuration ({@code <object className="NK2400">}) had no word left to be
     * found by, not even the code it is about.
     */
    public static String textVector(SearchLanguage language, String column) {
        return "to_tsvector('" + language.config() + "', translate(coalesce(" + column + ", ''), '<>', '  '))";
    }

    /**
     * The full-text index for the language: its name says the language and the expression's version (v2: with '<' and
     * '>' replaced) - so a check of the name tells whether the index fits, and the index of before
     * ("embedding_text_idx", German, without the replacement) is replaced on the first start.
     */
    static String textIndexName(SearchLanguage language) {
        return TEXT_INDEX_PREFIX + language.config() + "_v2_idx";
    }

    /**
     * Makes the table's full-text index the one for the language: on start, when the table is created and after the
     * tenant's language changed. Nothing to do while there is no table, or when the index is there and valid - a look
     * at the catalog. Otherwise every other full-text index goes, and the right one is built.
     * <p>
     * CONCURRENTLY: a change of the language comes while the tenant's people chat - its search goes on meanwhile,
     * without the index (right, slower), and nothing waits on a lock for the build (measured: 8 s for 200,000 chunks
     * without CONCURRENTLY, which reads the table twice). The build waits {@link #INDEX_LOCK_TIMEOUT} for other
     * transactions; broken off (an exception), it leaves an invalid index behind, which the next call drops and
     * builds again - the caller tries again ({@code DocumentIndexer.ensureTextIndex}).
     * <p>
     * A connection of its own, not the tenant's pool: on start it runs for every tenant, and a pool each would keep
     * hundreds of connections open and take the server's last ones.
     */
    public void ensureTextIndex(String databaseName, SearchLanguage language) {
        String wanted = textIndexName(language);
        try (Connection connection = databases.openConnection(databaseName, INDEX_LOCK_TIMEOUT);
             Statement statement = connection.createStatement()) {
            Map<String, Boolean> indexes = textIndexes(connection);
            if (indexes == null || Boolean.TRUE.equals(indexes.get(wanted)) && indexes.size() == 1) {
                return;
            }
            long started = System.currentTimeMillis();
            for (String index : indexes.keySet()) {
                if (!index.equals(wanted) || !indexes.get(index)) {
                    statement.execute("DROP INDEX CONCURRENTLY IF EXISTS " + index);
                }
            }
            statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + wanted + " ON " + TABLE
                    + " USING gin (" + textVector(language, "text") + ")");
            LOG.info("Full-text index of {} made the one for {} in {} ms", databaseName, language,
                    System.currentTimeMillis() - started);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not build the full-text index of " + databaseName, e);
        }
    }

    /**
     * The table's full-text indexes of ours - the one of before and the ones named for a language -, each with whether
     * it is valid; null while there is no table. Not langchain4j's own ({@code embedding_text_fts_gin_index}, built by
     * its hybrid store for the retrieval measurement): dropped here, its next search would build it again, in one
     * piece and on the tenant's pool.
     */
    private static Map<String, Boolean> textIndexes(Connection connection) throws SQLException {
        String sql = "SELECT i.relname, x.indisvalid FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid "
                + "WHERE x.indrelid = to_regclass('" + TABLE + "') AND (i.relname = '" + TEXT_INDEX_PREFIX + "idx' "
                + "OR i.relname LIKE '" + TEXT_INDEX_PREFIX + "%\\_v%\\_idx' ESCAPE '\\')";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT to_regclass('" + TABLE + "') IS NOT NULL")) {
            result.next();
            if (!result.getBoolean(1)) {
                return null;
            }
            Map<String, Boolean> indexes = new HashMap<>();
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    indexes.put(rows.getString(1), rows.getBoolean(2));
                }
            }
            return indexes;
        }
    }

    private void createIndexes(String databaseName, int dimension, SearchLanguage language) {
        ensureTextIndex(databaseName, language);
        // Every embedding run removes the document's previous chunks first, and so does every delete: without this
        // index each of them reads the tenant's whole table - an import of thousands of files once per file.
        // The expression in parentheses of its own: PostgreSQL takes a cast in an index only so.
        execute(databaseName, "CREATE INDEX IF NOT EXISTS " + DOCUMENT_INDEX + " ON " + TABLE + " ((" + CHUNK_DOCUMENT + "))");
        if (dimension > AiModelService.MAX_DIMENSION) {
            LOG.warn("Embeddings of {} have {} dimensions - more than an HNSW index takes; searched without one",
                    databaseName, dimension);
            return;
        }
        // Cosine, like langchain4j's search (embedding <=> query).
        execute(databaseName, "CREATE INDEX IF NOT EXISTS embedding_vector_idx ON " + TABLE
                + " USING hnsw (embedding vector_cosine_ops)");
    }

    private void execute(String databaseName, String sql) {
        try (Connection connection = databases.dataSource(databaseName).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not change the embeddings of " + databaseName, e);
        }
    }

    /** "WHERE"/"AND" and the scope's condition on the joined document d; nothing when the scope finds everything. */
    private static String scopeCondition(String keyword, KnowledgeScope scope) {
        return scope.findsEverything() ? "" : " " + keyword + " (d.area_id IS NULL OR d.area_id = ANY(?))";
    }

    /** Sets the scope's areas at index (if it has a condition); answers the next index. */
    private static int setScope(Connection connection, PreparedStatement statement, int index, KnowledgeScope scope)
            throws SQLException {
        if (scope.findsEverything()) {
            return index;
        }
        statement.setArray(index, connection.createArrayOf("bigint", scope.areaIdList().toArray()));
        return index + 1;
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }

    /**
     * A chunk a search found; id is its embedding_id, the same in both searches; documentChunks: how many its document
     * has. score: the vector search's (0..1), 0 from the full-text search.
     */
    public record ChunkMatch(String id, Long documentId, int documentChunks, String fileName, String text, double score) {
    }
}
