package org.lumongo.server.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.index.FacetFields;
import org.apache.lucene.facet.params.FacetSearchParams;
import org.apache.lucene.facet.search.CountFacetRequest;
import org.apache.lucene.facet.search.DrillDownQuery;
import org.apache.lucene.facet.search.DrillSideways;
import org.apache.lucene.facet.search.DrillSideways.DrillSidewaysResult;
import org.apache.lucene.facet.search.FacetResult;
import org.apache.lucene.facet.search.FacetResultNode;
import org.apache.lucene.facet.search.FacetsCollector;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.facet.taxonomy.directory.LumongoDirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.LumongoDirectoryTaxonomyWriter;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LumongoIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.lumongo.LumongoConstants;
import org.lumongo.cluster.message.Lumongo;
import org.lumongo.cluster.message.Lumongo.AssociatedDocument;
import org.lumongo.cluster.message.Lumongo.CountRequest;
import org.lumongo.cluster.message.Lumongo.DeleteRequest;
import org.lumongo.cluster.message.Lumongo.FacetCount;
import org.lumongo.cluster.message.Lumongo.FacetGroup;
import org.lumongo.cluster.message.Lumongo.FacetRequest;
import org.lumongo.cluster.message.Lumongo.FetchRequest.FetchType;
import org.lumongo.cluster.message.Lumongo.FieldSort;
import org.lumongo.cluster.message.Lumongo.FieldSort.Direction;
import org.lumongo.cluster.message.Lumongo.GetFieldNamesResponse;
import org.lumongo.cluster.message.Lumongo.GetTermsRequest;
import org.lumongo.cluster.message.Lumongo.GetTermsResponse;
import org.lumongo.cluster.message.Lumongo.IndexSettings;
import org.lumongo.cluster.message.Lumongo.LMDoc;
import org.lumongo.cluster.message.Lumongo.LMField;
import org.lumongo.cluster.message.Lumongo.ResultDocument;
import org.lumongo.cluster.message.Lumongo.ScoredResult;
import org.lumongo.cluster.message.Lumongo.SegmentCountResponse;
import org.lumongo.cluster.message.Lumongo.SegmentResponse;
import org.lumongo.cluster.message.Lumongo.SortRequest;
import org.lumongo.cluster.message.Lumongo.StoreRequest;
import org.lumongo.server.config.IndexConfig;
import org.lumongo.storage.rawfiles.MongoDocumentStorage;
import org.lumongo.util.LockHandler;
import org.lumongo.util.StringUtil;

public class Segment {
	
	private final static Logger log = Logger.getLogger(Segment.class);
	
	private final int segmentNumber;
	
	private final LumongoIndexWriter indexWriter;
	private LumongoDirectoryTaxonomyWriter taxonomyWriter;
	private LumongoDirectoryTaxonomyReader taxonomyReader;
	
	private final IndexConfig indexConfig;
	
	private final String uniqueIdField;
	
	private final AtomicLong counter;
	
	private Long lastCommit;
	private Long lastChange;
	private String indexName;
	private Analyzer analyzer;
	
	private final Set<String> fetchSet;
	
	private final FieldType notStoredTextField;
	
	private MongoDocumentStorage documentStorage;
	
	private LockHandler lockHandler;
	
	public Segment(int segmentNumber, MongoDocumentStorage documentStorage, LumongoIndexWriter indexWriter, LumongoDirectoryTaxonomyWriter taxonomyWriter,
					IndexConfig indexConfig, Analyzer analyzer) throws IOException {
		
		this.lockHandler = new LockHandler();
		
		this.segmentNumber = segmentNumber;
		this.documentStorage = documentStorage;
		this.indexWriter = indexWriter;
		
		this.taxonomyWriter = taxonomyWriter;
		if (this.taxonomyWriter != null) {
			this.taxonomyReader = new LumongoDirectoryTaxonomyReader(taxonomyWriter);
		}
		
		this.indexConfig = indexConfig;
		this.analyzer = analyzer;
		
		this.uniqueIdField = indexConfig.getUniqueIdField();
		
		this.fetchSet = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(uniqueIdField, LumongoConstants.TIMESTAMP_FIELD)));
		
		this.counter = new AtomicLong();
		this.lastCommit = null;
		this.lastChange = null;
		this.indexName = indexConfig.getIndexName();
		
		//term vectors enabled for sorting code
		notStoredTextField = new FieldType(TextField.TYPE_NOT_STORED);
		notStoredTextField.setStoreTermVectors(true);
		notStoredTextField.setStoreTermVectorOffsets(true);
		notStoredTextField.setStoreTermVectorPositions(true);
		//For PostingsHighlighter in Lucene 4.1 +
		//notStoredTextField.setIndexOptions(FieldInfo.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
		//example https://svn.apache.org/repos/asf/lucene/dev/trunk/lucene/highlighter/src/test/org/apache/lucene/search/postingshighlight/TestPostingsHighlighter.java
		notStoredTextField.freeze();
		
	}
	
	public void updateIndexSettings(IndexSettings indexSettings, Analyzer analyzer) {
		this.analyzer = analyzer;
		this.indexConfig.configure(indexSettings);
	}
	
	public int getSegmentNumber() {
		return segmentNumber;
	}
	
	public SegmentResponse querySegment(Query q, int amount, FieldDoc after, FacetRequest facetRequest, SortRequest sortRequest, boolean realTime)
					throws Exception {
		
		IndexReader ir = null;
		
		try {
			
			//ir = IndexReader.open(indexWriter, indexConfig.getApplyUncommitedDeletes());
			//ir = IndexReader.open(indexWriter.getDirectory());
			ir = indexWriter.getReader(indexConfig.getApplyUncommitedDeletes(), realTime);
			
			IndexSearcher is = new IndexSearcher(ir);
			
			Weight w = is.createNormalizedWeight(q);
			boolean docsScoredInOrder = !w.scoresDocsOutOfOrder();
			
			int hasMoreAmount = amount + 1;
			
			TopDocsCollector<?> collector;
			
			List<SortField> sortFields = new ArrayList<SortField>();
			boolean sorting = sortRequest != null && !sortRequest.getFieldSortList().isEmpty();
			if (sorting) {
				
				for (FieldSort fs : sortRequest.getFieldSortList()) {
					boolean reverse = Direction.DESCENDING.equals(fs.getDirection());
					
					String sortField = fs.getSortField();
					
					SortField.Type type = SortField.Type.STRING;
					if (indexConfig.isNumericField(sortField)) {
						if (indexConfig.isNumericIntField(sortField)) {
							type = SortField.Type.INT;
						}
						else if (indexConfig.isNumericLongField(sortField)) {
							type = SortField.Type.LONG;
						}
						else if (indexConfig.isNumericFloatField(sortField)) {
							type = SortField.Type.FLOAT;
						}
						else if (indexConfig.isNumericDoubleField(sortField)) {
							type = SortField.Type.DOUBLE;
						}
					}
					
					sortFields.add(new SortField(sortField, type, reverse));
				}
				Sort sort = new Sort();
				sort.setSort(sortFields.toArray(new SortField[0]));
				boolean fillFields = true;
				boolean trackDocScores = true;
				boolean trackMaxScore = true;
				collector = TopFieldCollector.create(sort, hasMoreAmount, after, fillFields, trackDocScores, trackMaxScore, docsScoredInOrder);
			}
			else {
				collector = TopScoreDocCollector.create(hasMoreAmount, after, docsScoredInOrder);
			}
			
			SegmentResponse.Builder builder = SegmentResponse.newBuilder();
			
			if (indexConfig.isFaceted() && facetRequest != null && !facetRequest.getCountRequestList().isEmpty()) {
				
				taxonomyReader = taxonomyReader.doOpenIfChanged(realTime);
				
				List<org.apache.lucene.facet.search.FacetRequest> facetRequests = new ArrayList<org.apache.lucene.facet.search.FacetRequest>(facetRequest
								.getCountRequestList().size());
				for (CountRequest count : facetRequest.getCountRequestList()) {
					int maxFacets = Integer.MAX_VALUE; //have to fetch all facets to merge between segments correctly
					facetRequests.add(new CountFacetRequest(new CategoryPath(count.getFacet(), LumongoConstants.FACET_DELIMITER), maxFacets));
				}
				
				FacetSearchParams facetSearchParams = new FacetSearchParams(facetRequests);
				
				List<FacetResult> facetResults;
				if (facetRequest.getDrillSideways()) {
					DrillSideways ds = new DrillSideways(is, taxonomyReader);
					DrillSidewaysResult ddsr = ds.search((DrillDownQuery) q, collector, facetSearchParams);
					facetResults = ddsr.facetResults;
				}
				else {
					FacetsCollector facetsCollector = FacetsCollector.create(facetSearchParams, ir, taxonomyReader);
					is.search(q, MultiCollector.wrap(collector, facetsCollector));
					facetResults = facetsCollector.getFacetResults();
				}
				
				for (FacetResult fc : facetResults) {
					String fullPath = StringUtil.join(LumongoConstants.FACET_DELIMITER, fc.getFacetRequest().categoryPath.components);
					FacetGroup.Builder fg = FacetGroup.newBuilder();
					fg.setFieldName(fullPath);
					for (FacetResultNode subResult : fc.getFacetResultNode().subResults) {
						FacetCount.Builder facetCountBuilder = FacetCount.newBuilder();
						CategoryPath cp = subResult.label;
						long count = (long) subResult.value;
						facetCountBuilder.setCount(count);
						facetCountBuilder.setFacet(cp.toString(LumongoConstants.FACET_DELIMITER));
						fg.addFacetCount(facetCountBuilder);
					}
					builder.addFacetGroup(fg);
				}
			}
			else {
				is.search(q, collector);
			}
			
			ScoreDoc[] results = collector.topDocs().scoreDocs;
			
			int totalHits = collector.getTotalHits();
			
			builder.setTotalHits(totalHits);
			
			boolean moreAvailable = (results.length == hasMoreAmount);
			
			int numResults = Math.min(results.length, amount);
			
			for (int i = 0; i < numResults; i++) {
				ScoredResult.Builder srBuilder = handleDocResult(is, sortRequest, sorting, results, i);
				
				builder.addScoredResult(srBuilder.build());
				
			}
			
			if (moreAvailable) {
				ScoredResult.Builder srBuilder = handleDocResult(is, sortRequest, sorting, results, numResults);
				builder.setNext(srBuilder);
			}
			
			builder.setIndexName(indexName);
			builder.setSegmentNumber(segmentNumber);
			return builder.build();
		}
		finally {
			if (ir != null) {
				ir.close();
			}
			
		}
		
	}
	
	private ScoredResult.Builder handleDocResult(IndexSearcher is, SortRequest sortRequest, boolean sorting, ScoreDoc[] results, int i)
					throws CorruptIndexException, IOException {
		int docId = results[i].doc;
		Document d = is.doc(docId, fetchSet);
		ScoredResult.Builder srBuilder = ScoredResult.newBuilder();
		srBuilder.setScore(results[i].score);
		srBuilder.setUniqueId(d.get(indexConfig.getUniqueIdField()));
		
		IndexableField f = d.getField(LumongoConstants.TIMESTAMP_FIELD);
		srBuilder.setTimestamp(f.numericValue().longValue());
		
		srBuilder.setDocId(docId);
		srBuilder.setSegment(segmentNumber);
		srBuilder.setIndexName(indexName);
		srBuilder.setResultIndex(i);
		if (sorting) {
			FieldDoc result = (FieldDoc) results[i];
			
			int c = 0;
			for (Object o : result.fields) {
				FieldSort fieldSort = sortRequest.getFieldSort(c);
				String sortField = fieldSort.getSortField();
				if (indexConfig.isNumericField(sortField)) {
					if (indexConfig.isNumericIntField(sortField)) {
						if (o == null) {
							srBuilder.addSortInteger(0); // TODO what should nulls value be?
						}
						else {
							srBuilder.addSortInteger((Integer) o);
						}
					}
					else if (indexConfig.isNumericLongField(sortField)) {
						if (o == null) {
							srBuilder.addSortLong(0L);// TODO what should nulls value be?
						}
						else {
							srBuilder.addSortLong((Long) o);
						}
					}
					else if (indexConfig.isNumericFloatField(sortField)) {
						if (o == null) {
							srBuilder.addSortFloat(0f);// TODO what should nulls value be?
						}
						else {
							srBuilder.addSortFloat((Float) o);
						}
					}
					else if (indexConfig.isNumericDoubleField(sortField)) {
						if (o == null) {
							srBuilder.addSortDouble(0);// TODO what should nulls value be?
						}
						else {
							srBuilder.addSortDouble((Double) o);
						}
					}
				}
				else {
					if (o == null) {
						srBuilder.addSortTerm(""); // TODO what should nulls value be?
					}
					else {
						BytesRef b = (BytesRef) o;
						srBuilder.addSortTerm(b.utf8ToString());
					}
				}
				
				c++;
			}
		}
		return srBuilder;
	}
	
	private void possibleCommit() throws CorruptIndexException, IOException {
		lastChange = System.currentTimeMillis();
		
		long count = counter.incrementAndGet();
		if (count % indexConfig.getSegmentCommitInterval() == 0) {
			forceCommit();
		}
		else if (count % indexConfig.getSegmentFlushInterval() == 0) {
			if (indexConfig.isFaceted()) {
				taxonomyWriter.flush();
			}
			indexWriter.flush(indexConfig.getApplyUncommitedDeletes());
		}
	}
	
	public void forceCommit() throws CorruptIndexException, IOException {
		long currentTime = System.currentTimeMillis();
		if (indexConfig.isFaceted()) {
			taxonomyWriter.commit();
		}
		
		indexWriter.commit();
		lastCommit = currentTime;
		
	}
	
	public void doCommit() throws CorruptIndexException, IOException {
		
		long currentTime = System.currentTimeMillis();
		
		Long lastCh = lastChange;
		// if changes since started
		
		if (lastCh != null) {
			if ((currentTime - lastCh) > (indexConfig.getIdleTimeWithoutCommit() * 1000)) {
				if (lastCommit == null || lastCh > lastCommit) {
					log.info("Flushing segment <" + segmentNumber + "> for index <" + indexName + ">");
					forceCommit();
				}
			}
		}
	}
	
	public void close() throws CorruptIndexException, IOException {
		forceCommit();
		if (indexConfig.isFaceted()) {
			taxonomyWriter.close();
		}
		
		indexWriter.close();
	}
	
	private void index(String uniqueId, LMDoc lmDoc, long timestamp) throws CorruptIndexException, IOException {
		Document d = new Document();
		
		for (LMField indexedField : lmDoc.getIndexedFieldList()) {
			String fieldName = indexedField.getFieldName();
			
			if (!indexConfig.isNumericField(fieldName)) {
				List<String> fieldValueList = indexedField.getFieldValueList();
				for (String fieldValue : fieldValueList) {
					d.add(new Field(fieldName, fieldValue, notStoredTextField));
				}
			}
			else {
				if (indexConfig.isNumericIntField(fieldName)) {
					List<Integer> valueList = indexedField.getIntValueList();
					for (int value : valueList) {
						d.add(new IntField(fieldName, value, Store.YES));
					}
				}
				else if (indexConfig.isNumericLongField(fieldName)) {
					List<Long> valueList = indexedField.getLongValueList();
					for (long value : valueList) {
						d.add(new LongField(fieldName, value, Store.YES));
					}
				}
				else if (indexConfig.isNumericFloatField(fieldName)) {
					List<Float> valueList = indexedField.getFloatValueList();
					for (float value : valueList) {
						d.add(new FloatField(fieldName, value, Store.YES));
					}
				}
				else if (indexConfig.isNumericDoubleField(fieldName)) {
					List<Double> valueList = indexedField.getDoubleValueList();
					for (double value : valueList) {
						d.add(new DoubleField(fieldName, value, Store.YES));
					}
				}
				else {
					//should be impossible
					throw new RuntimeException("Unsupported numeric field type for field <" + fieldName + ">");
				}
				
			}
		}
		d.removeFields(indexConfig.getUniqueIdField());
		d.add(new TextField(indexConfig.getUniqueIdField(), uniqueId, Store.NO));
		
		//make sure the update works because it is searching on a term
		d.add(new StringField(indexConfig.getUniqueIdField(), uniqueId, Store.YES));
		
		d.add(new LongField(LumongoConstants.TIMESTAMP_FIELD, timestamp, Store.YES));
		
		if (indexConfig.isFaceted()) {
			if (lmDoc.getFacetCount() != 0) {
				List<CategoryPath> categories = new ArrayList<CategoryPath>();
				for (String facet : lmDoc.getFacetList()) {
					categories.add(new CategoryPath(facet, LumongoConstants.FACET_DELIMITER));
				}
				FacetFields facetFields = new FacetFields(taxonomyWriter);
				facetFields.addFields(d, categories);
			}
		}
		else {
			if (lmDoc.getFacetCount() != 0) {
				throw new IOException("Cannot store facets into a non faceted index");
			}
		}
		
		Term term = new Term(indexConfig.getUniqueIdField(), uniqueId);
		indexWriter.updateDocument(term, d, analyzer);
		
		possibleCommit();
	}
	
	public void deleteDocument(DeleteRequest deleteRequest) throws Exception {
		String uniqueId = deleteRequest.getUniqueId();
		
		ReadWriteLock lock = lockHandler.getLock(uniqueId);
		
		try {
			lock.writeLock().lock();
			
			if (deleteRequest.getDeleteDocument()) {
				Term term = new Term(uniqueIdField, uniqueId);
				indexWriter.deleteDocuments(term);
				possibleCommit();
				documentStorage.deleteSourceDocument(uniqueId);
			}
			
			if (deleteRequest.getDeleteAllAssociated()) {
				documentStorage.deleteAssociatedDocuments(uniqueId);
			}
			else if (deleteRequest.hasFilename()) {
				String fileName = deleteRequest.getFilename();
				documentStorage.deleteAssociatedDocument(uniqueId, fileName);
			}
		}
		finally {
			lock.writeLock().unlock();
		}
		
	}
	
	public void optimize() throws CorruptIndexException, IOException {
		lastChange = System.currentTimeMillis();
		indexWriter.forceMerge(1);
		forceCommit();
	}
	
	public GetFieldNamesResponse getFieldNames() throws CorruptIndexException, IOException {
		GetFieldNamesResponse.Builder builder = GetFieldNamesResponse.newBuilder();
		
		DirectoryReader ir = DirectoryReader.open(indexWriter, indexConfig.getApplyUncommitedDeletes());
		
		Set<String> fields = new HashSet<String>();
		
		for (AtomicReaderContext subreaderContext : ir.leaves()) {
			FieldInfos fieldInfos = subreaderContext.reader().getFieldInfos();
			for (FieldInfo fi : fieldInfos) {
				String fieldName = fi.name;
				fields.add(fieldName);
			}
		}
		
		for (String fieldName : fields) {
			builder.addFieldName(fieldName);
		}
		
		return builder.build();
	}
	
	public void clear() throws IOException {
		//index has write lock so none needed here
		indexWriter.deleteAll();
		documentStorage.deleteAllDocuments();
		forceCommit();
		
	}
	
	public GetTermsResponse getTerms(GetTermsRequest request) throws IOException {
		GetTermsResponse.Builder builder = GetTermsResponse.newBuilder();
		
		DirectoryReader ir = null;
		try {
			ir = indexWriter.getReader(indexConfig.getApplyUncommitedDeletes(), request.getRealTime());
			
			String fieldName = request.getFieldName();
			String startTerm = "";
			
			if (request.hasStartingTerm()) {
				startTerm = request.getStartingTerm();
			}
			
			BytesRef startTermBytes = new BytesRef(startTerm);
			
			SortedMap<String, AtomicLong> termsMap = new TreeMap<String, AtomicLong>();
			
			for (AtomicReaderContext subreaderContext : ir.leaves()) {
				Fields fields = subreaderContext.reader().fields();
				if (fields != null) {
					
					Terms terms = fields.terms(fieldName);
					if (terms != null) {
						//TODO reuse?
						TermsEnum termsEnum = terms.iterator(null);
						SeekStatus seekStatus = termsEnum.seekCeil(startTermBytes);
						
						BytesRef text;
						if (!seekStatus.equals(SeekStatus.END)) {
							text = termsEnum.term();
							String textStr = text.utf8ToString();
							if (!termsMap.containsKey(textStr)) {
								termsMap.put(textStr, new AtomicLong());
							}
							termsMap.get(textStr).addAndGet(termsEnum.docFreq());
							
							while ((text = termsEnum.next()) != null) {
								textStr = text.utf8ToString();
								if (!termsMap.containsKey(textStr)) {
									termsMap.put(textStr, new AtomicLong());
								}
								termsMap.get(textStr).addAndGet(termsEnum.docFreq());
								
							}
							
						}
					}
				}
				
			}
			
			int amount = Math.min(request.getAmount(), termsMap.size());
			
			int i = 0;
			for (String term : termsMap.keySet()) {
				AtomicLong docFreq = termsMap.get(term);
				builder.addTerm(Lumongo.Term.newBuilder().setValue(term).setDocFreq(docFreq.get()));
				
				//TODO remove the limit and paging and just return all?
				i++;
				if (i > amount) {
					break;
				}
				
			}
			
			return builder.build();
		}
		finally {
			if (ir != null) {
				ir.close();
			}
		}
	}
	
	public SegmentCountResponse getNumberOfDocs(boolean realTime) throws CorruptIndexException, IOException {
		IndexReader ir = null;
		
		try {
			ir = indexWriter.getReader(indexConfig.getApplyUncommitedDeletes(), realTime);
			int count = ir.numDocs();
			return SegmentCountResponse.newBuilder().setNumberOfDocs(count).setSegmentNumber(segmentNumber).build();
		}
		finally {
			if (ir != null) {
				ir.close();
			}
		}
	}
	
	public List<AssociatedDocument> getAssociatedDocuments(String uniqueId, FetchType associatedFetchType) throws Exception {
		return documentStorage.getAssociatedDocuments(uniqueId, associatedFetchType);
	}
	
	public AssociatedDocument getAssociatedDocument(String uniqueId, String fileName, FetchType associatedFetchType) throws Exception {
		return documentStorage.getAssociatedDocument(uniqueId, fileName, associatedFetchType);
	}
	
	public ResultDocument getSourceDocument(String uniqueId, FetchType resultFetchType) throws Exception {
		return documentStorage.getSourceDocument(uniqueId, resultFetchType);
	}
	
	public void storeAssociatedDocument(String uniqueId, String fileName, InputStream is, boolean compress, long clusterTime,
					HashMap<String, String> metadataMap) throws Exception {
		documentStorage.storeAssociatedDocument(uniqueId, fileName, is, compress, clusterTime, metadataMap);
	}
	
	public InputStream getAssociatedDocumentStream(String uniqueId, String fileName) throws IOException {
		return documentStorage.getAssociatedDocumentStream(uniqueId, fileName);
	}
	
	public void store(StoreRequest storeRequest, long timestamp) throws Exception {
		
		String uniqueId = storeRequest.getUniqueId();
		ReadWriteLock lock = lockHandler.getLock(uniqueId);
		
		try {
			lock.writeLock().lock();
			
			if (storeRequest.hasIndexedDocument()) {
				LMDoc lmDoc = storeRequest.getIndexedDocument();
				this.index(uniqueId, lmDoc, timestamp);
			}
			if (storeRequest.hasResultDocument()) {
				ResultDocument rd = ResultDocument.newBuilder(storeRequest.getResultDocument()).setTimestamp(timestamp).build();
				documentStorage.storeSourceDocument(rd);
			}
			
			if (storeRequest.getClearExistingAssociated()) {
				documentStorage.deleteAssociatedDocuments(uniqueId);
			}
			
			for (AssociatedDocument ad : storeRequest.getAssociatedDocumentList()) {
				ad = AssociatedDocument.newBuilder(ad).setTimestamp(timestamp).build();
				documentStorage.storeAssociatedDocument(ad);
			}
		}
		finally {
			lock.writeLock().unlock();
		}
	}
	
}
