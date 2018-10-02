package gov.nysenate.openleg.dao.law.search;

import gov.nysenate.openleg.client.view.law.LawDocView;
import gov.nysenate.openleg.dao.base.ElasticBaseDao;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SearchIndex;
import gov.nysenate.openleg.model.law.LawDocId;
import gov.nysenate.openleg.model.law.LawDocument;
import gov.nysenate.openleg.model.search.SearchResults;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.rescore.RescorerBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** {@inheritDoc} */
@Repository
public class ElasticLawSearchDao extends ElasticBaseDao implements LawSearchDao
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticLawSearchDao.class);

    protected static String lawIndexName = SearchIndex.LAW.getIndexName();

    protected static List<HighlightBuilder.Field> highlightFields =
        Arrays.asList(new HighlightBuilder.Field("text").numOfFragments(5),
                      new HighlightBuilder.Field("title").numOfFragments(0));

    /** {@inheritDoc} */
    @Override
    public SearchResults<LawDocId> searchLawDocs(QueryBuilder query, QueryBuilder postFilter,
                                                 RescorerBuilder rescorer, List<SortBuilder> sort, LimitOffset limOff) {
        return search(lawIndexName, query, postFilter,
                highlightFields, rescorer,
                sort, limOff,
                true, this::getLawDocIdFromHit);
    }

    /** {@inheritDoc} */
    @Override
    public void updateLawIndex(LawDocument lawDoc) {
        if (lawDoc != null) {
            updateLawIndex(Collections.singletonList(lawDoc));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updateLawIndex(Collection<LawDocument> lawDocs) {
        if (lawDocs != null && !lawDocs.isEmpty()) {
            BulkRequest bulkRequest = new BulkRequest();
            lawDocs.stream().map(LawDocView::new)
                    .map(docView -> getJsonIndexRequest(lawIndexName, createSearchId(docView), docView))
                    .forEach(bulkRequest::add);
            safeBulkRequestExecute(bulkRequest);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteLawDocFromIndex(LawDocId lawDocId) {
        if (lawDocId != null) {
            deleteEntry(lawIndexName, createSearchId(lawDocId));
        }
    }

    /** {@inheritDoc} */
    @Override
    protected List<String> getIndices() {
        return Collections.singletonList(lawIndexName);
    }

    /**
     * Allocate additional shards for law index.
     *
     * @return Settings.Builder
     */
    @Override
    protected Settings.Builder getIndexSettings() {
        Settings.Builder indexSettings = super.getIndexSettings();
        indexSettings.put("index.number_of_shards", 2);
        return indexSettings;
    }


    @Override
    protected HashMap<String, Object> getCustomMappingProperties() throws IOException {
        HashMap<String, Object> props = super.getCustomMappingProperties();
        props.put("docLevelId", searchableKeywordMapping);
        props.put("docType", searchableKeywordMapping);
        props.put("lawId", searchableKeywordMapping);
        props.put("lawName", searchableKeywordMapping);
        props.put("locationId", searchableKeywordMapping);
        return props;
    }

    /* --- Internal --- */

    private LawDocId getLawDocIdFromHit(SearchHit hit) {
        String docId = hit.getId();
        return new LawDocId(docId, LocalDate.parse((String) hit.getSourceAsMap().get("activeDate")));
    }

    private String createSearchId(LawDocId lawDocId) {
        return lawDocId.getLocationId();
    }

    private String createSearchId(LawDocView lawDocView) {
        return lawDocView.getLocationId();
    }
}
