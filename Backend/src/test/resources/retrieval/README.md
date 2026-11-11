# Retrieval test corpus

A fictional company - "Nordhang Heiztechnik GmbH", a maker and installer of gas condensing boilers - with 16 documents
and 43 questions, to measure how well the knowledge search finds the passage that answers a question
(`services/document/RetrievalEvaluationSpec`). Every name, number and rule in it is made up.

- `corpus/` - the documents as Markdown, CSV or text; `corpus.json` says under which file name and in which format
  (PDF, DOCX, XLSX, CSV, TXT, MD, HTML - rendered by `FileRenderer`) each is uploaded, so the real extraction runs.
- `questions.json` - each question with its type and the passages that answer it (file and a phrase the found chunk
  must contain; several entries = any of them). Types: EXACT (codes, article numbers), TABLE (a row of a table), SPLIT
  (deep in a long step list), PARAPHRASE (other words than the document), DISTRACTOR (two similar documents with
  different values), CODE (a configuration of XML blocks and property tables with hardly a sentence in it - an
  overview with front matter and emojis whose include directive points at it, as documentation sites keep them),
  NONE (no answer in the documents - nothing should be found).

The measurement needs a real embedding model and is skipped without a key or a local Ollama with `bge-m3`; with
`EVAL_RERANK_URL` (a server speaking TEI's `/rerank`) the hybrid search is measured with the reranker too:

```bash
EVAL_OPENAI_KEY=sk-... ./gradlew test --tests "de.alpdesk.services.document.RetrievalEvaluationSpec"
EVAL_GEMINI_KEY=... ./gradlew test --tests "de.alpdesk.services.document.RetrievalEvaluationSpec"
EVAL_OLLAMA_URL=http://127.0.0.1:11434 EVAL_RERANK_URL=http://127.0.0.1:19185 \
  ./gradlew test --tests "de.alpdesk.services.document.RetrievalEvaluationSpec"
```

It writes `build/reports/retrieval/<model>.md`: per question type how often the answering passage is the first hit and
among the first five, the mean reciprocal rank, and for questions without an answer how often something was found.
