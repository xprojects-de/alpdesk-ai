package de.alpdesk.services.filestore

import spock.lang.Specification

/**
 * An FTPS listing is bounded in bytes (docs/architecture/file-stores.md "Sessions"): Commons Net reads a listing whole
 * before it parses it, so a server answering without end is cut off at FtpsStoreClient.LISTING_MAX_BYTES - the
 * session turns that into fileStore.tooManyEntries. No server: the bound is the stream the data connection hands out.
 */
class FtpsListingBoundSpec extends Specification {

    def "a listing within the bound is read to its end, one byte more ends it"() {
        given:
        def within = new FtpsStoreClient.BoundedListing(new ByteArrayInputStream(new byte[FtpsStoreClient.LISTING_MAX_BYTES]))
        def beyond = new FtpsStoreClient.BoundedListing(new ByteArrayInputStream(new byte[FtpsStoreClient.LISTING_MAX_BYTES + 1]))

        expect:
        within.readAllBytes().length == FtpsStoreClient.LISTING_MAX_BYTES

        when:
        beyond.readAllBytes()

        then:
        thrown(FtpsStoreClient.ListingTooLong)
    }

    def "the bound counts single bytes too"() {
        given:
        def bounded = new FtpsStoreClient.BoundedListing(new ByteArrayInputStream(new byte[FtpsStoreClient.LISTING_MAX_BYTES + 1]))
        bounded.readNBytes(FtpsStoreClient.LISTING_MAX_BYTES)

        when:
        bounded.read()

        then:
        thrown(FtpsStoreClient.ListingTooLong)
    }
}
