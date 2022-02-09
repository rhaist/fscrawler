/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.fs.client;


import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import fr.pilato.elasticsearch.crawler.fs.beans.Doc;
import fr.pilato.elasticsearch.crawler.fs.framework.Version;
import fr.pilato.elasticsearch.crawler.fs.framework.bulk.FsCrawlerBulkProcessor;
import fr.pilato.elasticsearch.crawler.fs.framework.bulk.FsCrawlerRetryBulkProcessorListener;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.logging.LoggingFeature;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.*;
import static fr.pilato.elasticsearch.crawler.fs.framework.JsonUtil.*;

/**
 * Elasticsearch Client for Clusters running v7.
 */
public class ElasticsearchClient implements IElasticsearchClient {

    private static final Logger logger = LogManager.getLogger(ElasticsearchClient.class);
    private final Path config;
    private final FsSettings settings;
    private static final String USER_AGENT = "FSCrawler-Rest-Client-" + Version.getVersion();


    private Client client = null;
    private FsCrawlerBulkProcessor<ElasticsearchOperation, ElasticsearchBulkRequest, ElasticsearchBulkResponse> bulkProcessor = null;
    private final AtomicInteger nodeNumber;
    private final List<String> hosts;

    /**
     * Type name for Elasticsearch versions >= 6.0
     * @deprecated Will be removed with Elasticsearch V8
     */
    @Deprecated
    private static final String INDEX_TYPE_DOC = "_doc";
    private String majorVersion;

    public ElasticsearchClient(Path config, FsSettings settings) {
        this.config = config;
        this.settings = settings;
        this.nodeNumber = new AtomicInteger();
        this.hosts = new ArrayList<>(settings.getElasticsearch().getNodes().size());
        settings.getElasticsearch().getNodes().forEach(node -> hosts.add(node.decodedUrl()));
    }

    @Override
    public String compatibleVersion() {
        return "7";
    }

    @Override
    public void start() throws IOException {
        if (client != null) {
            // The client has already been initialized. Let's skip this again
            return;
        }

        // Create the client
        ClientConfig config = new ClientConfig();
        // We need to suppress this so we can do DELETE with body
        config.property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION, true);
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(
                settings.getElasticsearch().getUsername(),
                settings.getElasticsearch().getPassword());
        client = ClientBuilder.newClient(config);
        if (logger.isTraceEnabled()) {
            client.property(LoggingFeature.LOGGING_FEATURE_VERBOSITY_CLIENT, LoggingFeature.Verbosity.PAYLOAD_ANY)
                    .property(LoggingFeature.LOGGING_FEATURE_LOGGER_LEVEL_CLIENT, "FINER");
        }
        client.register(feature);

        try {
            String esVersion = getVersion();
            majorVersion = extractMajorVersion(esVersion);
            logger.info("Elasticsearch Client connected to a node running version {}", esVersion);
        } catch (Exception e) {
            logger.warn("failed to create elasticsearch client on {}, disabling crawler...", settings.getElasticsearch().toString());
            throw e;
        }

        if (settings.getElasticsearch().getPipeline() != null) {
            // Check that the pipeline exists
            if (!isExistingPipeline(settings.getElasticsearch().getPipeline())) {
                throw new RuntimeException("You defined pipeline:" + settings.getElasticsearch().getPipeline() +
                        ", but it does not exist.");
            }
        }

        // Create the BulkProcessor instance
        bulkProcessor = new FsCrawlerBulkProcessor.Builder<>(
                new ElasticsearchEngine(this),
                new FsCrawlerRetryBulkProcessorListener<>("es_rejected_execution_exception"),
                ElasticsearchBulkRequest::new)
                .setBulkActions(settings.getElasticsearch().getBulkSize())
                .setFlushInterval(settings.getElasticsearch().getFlushInterval())
                .build();
    }

    @Override
    public String getVersion() {
        logger.debug("get version");
        String response = httpGet(null);
        // We parse the response
        Object document = parseJson(response);
        return JsonPath.read(document, "$.version.number");
    }

    /**
     * Create an index
     * @param index index name
     * @param ignoreErrors don't fail if the index already exists
     * @param indexSettings index settings if any
     */
    @Override
    public void createIndex(String index, boolean ignoreErrors, String indexSettings) throws ElasticsearchClientException {
        String realIndexSettings = indexSettings;
        logger.debug("create index [{}]", index);
        if (indexSettings == null) {
            // We need to pass an empty body because PUT requires a body
            realIndexSettings = "{}";
        }
        logger.trace("index settings: [{}]", realIndexSettings);
        try {
            httpPut(index, realIndexSettings);
            waitForHealthyIndex(index);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
                logger.debug("Response for create index [{}]: {}", index, e.getMessage());
                Object document = parseJson(e.getResponse().readEntity(String.class));
                String errorType = JsonPath.read(document, "$.error.type");
                if (errorType.contains("resource_already_exists_exception") && !ignoreErrors) {
                    throw new ElasticsearchClientException("index already exists");
                }
            } else {
                throw new ElasticsearchClientException("Error while creating index " + index, e);
            }
        }
    }

    /**
     * Check if an index exists
     * @param index index name
     * @return true if the index exists, false otherwise
     */
    @Override
    public boolean isExistingIndex(String index) {
        logger.debug("is existing index [{}]", index);
        try {
            httpGet(index);
            logger.debug("Index [{}] was found", index);
            return true;
        } catch (NotFoundException e) {
            logger.debug("Index [{}] was not found", index);
            return false;
        }
    }

    /**
     * Check if a pipeline exists
     * @param pipelineName pipeline name
     * @return true if the pipeline exists, false otherwise
     */
    @Override
    public boolean isExistingPipeline(String pipelineName) {
        logger.debug("is existing pipeline [{}]", pipelineName);
        try {
            httpGet("_ingest/pipeline/" + pipelineName);
            logger.debug("Pipeline [{}] was found", pipelineName);
            return true;
        } catch (NotFoundException e) {
            logger.debug("Pipeline [{}] was not found", pipelineName);
            return false;
        }
    }

    /**
     * Refresh an index
     * @param index index name
     * @throws ElasticsearchClientException In case of error
     */
    @Override
    public void refresh(String index) throws ElasticsearchClientException {
        logger.debug("refresh index [{}]", index);
        String url = index + "/_refresh";
        if (index == null) {
            url = "_refresh";
        }
        String response = httpPost(url, null);
        Object document = parseJson(response);
        int shardsFailed = JsonPath.read(document, "$._shards.failed");
        if (shardsFailed > 0) {
            throw new ElasticsearchClientException("Unable to refresh index " + index + " : " + response);
        }
    }

    /**
     * Wait for an index to become at least yellow (all primaries assigned), up to 5 seconds
     * @param index index name
     */
    @Override
    public void waitForHealthyIndex(String index) {
        logger.debug("wait for yellow health on index [{}]", index);
        httpGet("_cluster/health/" + index,
                new AbstractMap.SimpleImmutableEntry<>("wait_for_status", "yellow"),
                new AbstractMap.SimpleImmutableEntry<>("timeout", "5s"));
    }

    // Utility methods

    @Override
    public boolean isIngestSupported() {
        return true;
    }

    @Override
    public String getDefaultTypeName() {
        return INDEX_TYPE_DOC;
    }

    private static final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() { return null; }
    }};

    public static class NullHostNameVerifier implements HostnameVerifier {

        @Override
        public boolean verify(String arg0, SSLSession arg1) { return true; }

    }

    @Override
    public void index(String index, String id, Doc doc, String pipeline) {
        String json = serialize(doc);
        indexRawJson(index, id, json, pipeline);
    }

    @Override
    public void indexRawJson(String index, String id, String json, String pipeline) {
        logger.trace("JSon indexed : {}", json);
        bulkProcessor.add(new ElasticsearchIndexOperation(index, id, pipeline, json));
    }

    @Override
    public void indexSingle(String index, String id, String json, String pipeline) throws IOException {
        logger.trace("JSon indexed : {}", json);
        String url = index + "/" + INDEX_TYPE_DOC + "/" + id;
        if (!isNullOrEmpty(pipeline)) {
            url += "?pipeline=" + pipeline;
        }
        httpPut(url, json);
    }

    @Override
    public void delete(String index, String id) {
        bulkProcessor.add(new ElasticsearchDeleteOperation(index, id));
    }

    @Override
    public void deleteSingle(String index, String id) throws ElasticsearchClientException {
        logger.debug("Removing document : {}/{}", index, id);
        try {
            String response = httpDelete(index + "/" + INDEX_TYPE_DOC + "/" + id, null);

            DocumentContext document = parseJsonAsDocumentContext(response);
            String result = document.read("$.result");
            if (!result.equals("deleted")) {
                throw new ElasticsearchClientException("Can not remove document " + index + "/" + id + " cause: " + response);
            }

            logger.debug("Document {}/{} has been removed", index, id);
        } catch (NotFoundException e) {
            logger.debug("Document {}/{} does not exist. It can't be removed.", index, id);
            throw new ElasticsearchClientException("Document " + index + "/" + id + " does not exist");
        }
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing Elasticsearch client manager");
        if (bulkProcessor != null) {
            bulkProcessor.close();
        }
        if (client != null) {
            client.close();
        }
    }

    /*
    private static RestClientBuilder buildRestClient(Elasticsearch settings) {
        List<HttpHost> hosts = new ArrayList<>(settings.getNodes().size());
        settings.getNodes().forEach(node -> hosts.add(HttpHost.create(node.decodedUrl())));

        RestClientBuilder builder = RestClient.builder(hosts.toArray(new HttpHost[0]));

        if (settings.getPathPrefix() != null) {
            builder.setPathPrefix(settings.getPathPrefix());
        }

        if (settings.getUsername() != null) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(settings.getUsername(), settings.getPassword()));
            if (settings.getSslVerification()) {
                builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
            } else {
                builder.setHttpClientConfigCallback(httpClientBuilder -> {
                    SSLContext sc;
                    try {
                        sc = SSLContext.getInstance("SSL");
                        sc.init(null, trustAllCerts, new SecureRandom());
                    } catch (KeyManagementException | NoSuchAlgorithmException e) {
                        logger.warn("Failed to get SSL Context", e);
                        throw new RuntimeException(e);
                    }
                    httpClientBuilder.setSSLStrategy(new SSLIOSessionStrategy(sc, new NullHostNameVerifier()));
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    return httpClientBuilder;
                });
            }
        }

        return builder;
    }
    */

    @Override
    public void createIndices() throws Exception {
        Path jobMappingDir = config.resolve(settings.getName()).resolve("_mappings");

        // If needed, we create the new settings for this files index
        if (!settings.getFs().isAddAsInnerObject() || (!settings.getFs().isJsonSupport() && !settings.getFs().isXmlSupport())) {
            createIndex(jobMappingDir, majorVersion, INDEX_SETTINGS_FILE, settings.getElasticsearch().getIndex());
        } else {
            createIndex(settings.getElasticsearch().getIndex(), true, null);
        }

        // If needed, we create the new settings for this folder index
        if (settings.getFs().isIndexFolders()) {
            createIndex(jobMappingDir, majorVersion, INDEX_SETTINGS_FOLDER_FILE, settings.getElasticsearch().getIndexFolder());
        } else {
            createIndex(settings.getElasticsearch().getIndexFolder(), true, null);
        }
    }

    @Override
    public ESSearchResponse search(ESSearchRequest request) throws IOException, ElasticsearchClientException {

        String url = "";

        if (!isNullOrEmpty(request.getIndex())) {
            url += request.getIndex();
        }

        url += "/_search";

        final AtomicReference<String> body = new AtomicReference<>("{");

        boolean bodyEmpty = true;

        int size = 10;
        if (request.getSize() != null) {
            size = request.getSize();
            body.getAndUpdate(s -> s += "\"size\":" + request.getSize());
            bodyEmpty = false;
        }
        if (!request.getStoredFields().isEmpty()) {
            if (!bodyEmpty) {
                body.getAndUpdate(s -> s += ",");
            }
            body.getAndUpdate(s -> s += "\"stored_fields\" : [");

            AtomicBoolean moreFields = new AtomicBoolean(false);
            request.getStoredFields().forEach(f -> {
                if (moreFields.getAndSet(true)) {
                    body.getAndUpdate(s -> s += ",");
                }
                body.getAndUpdate(s -> s += "\"" + f + "\"");
            });
            body.getAndUpdate(s -> s += "]");
            bodyEmpty = false;
        }
        if (request.getESQuery() != null) {
            if (!bodyEmpty) {
                body.getAndUpdate(s -> s += ",");
            }
            body.getAndUpdate(s -> s += "\"query\" : {" + toElasticsearchQuery(request.getESQuery()) + "}");
            bodyEmpty = false;
        }
        if (!isNullOrEmpty(request.getSort())) {
            if (!bodyEmpty) {
                body.getAndUpdate(s -> s += ",");
            }
            body.getAndUpdate(s -> s += "\"sort\" : [\"" + request.getSort() + "\"]");
            bodyEmpty = false;
        }
        if (!request.getHighlighters().isEmpty()) {
            if (!bodyEmpty) {
                body.getAndUpdate(s -> s += ",");
            }
            body.getAndUpdate(s -> s += "\"highlight\": { \"fields\": {");

            AtomicBoolean moreFields = new AtomicBoolean(false);
            request.getHighlighters().forEach(f -> {
                if (moreFields.getAndSet(true)) {
                    body.getAndUpdate(s -> s += ",");
                }
                body.getAndUpdate(s -> s += "\"" + f + "\":{}");
            });
            body.getAndUpdate(s -> s += "}}");
            bodyEmpty = false;
        }
        if (!request.getAggregations().isEmpty()) {
            if (!bodyEmpty) {
                body.getAndUpdate(s -> s += ",");
            }
            body.getAndUpdate(s -> s += "\"aggs\": {");

            AtomicBoolean moreFields = new AtomicBoolean(false);
            request.getAggregations().forEach(a -> {
                if (moreFields.getAndSet(true)) {
                    body.getAndUpdate(s -> s += ",");
                }
                body.getAndUpdate(s -> s += "\"" + a.getName() + "\":{\"terms\": {\"field\": \"" + a.getField() + "\"}}");
            });
            body.getAndUpdate(s -> s += "}");
        }

        String query = body.updateAndGet(s -> s += "}");
        logger.trace("Elasticsearch query to run: {}", query);

        try {

            String response = httpPost(url, query, new AbstractMap.SimpleImmutableEntry<>("version", "true"));
            ESSearchResponse esSearchResponse = new ESSearchResponse(response);

            // Parse
            DocumentContext document = parseJsonAsDocumentContext(response);
            esSearchResponse.setTotalHits(document.read("$.hits.total.value"));

            int numHits = document.read("$.hits.hits.length()");
            if (numHits < size) {
                size = numHits;
            }
            for (int hitNum = 0; hitNum < size; hitNum++) {
                final ESSearchHit esSearchHit = new ESSearchHit();
                esSearchHit.setIndex(document.read("$.hits.hits[" + hitNum + "]._index"));
                esSearchHit.setId(document.read("$.hits.hits[" + hitNum + "]._id"));
                esSearchHit.setVersion(Integer.toUnsignedLong(document.read("$.hits.hits[" + hitNum + "]._version")));
                try {
                    esSearchHit.setSource(extractJsonFromPath(document, "$.hits.hits[" + hitNum + "]._source"));
                    esSearchHit.setSourceAsMap(document.read("$.hits.hits[" + hitNum + "]._source"));
                } catch (PathNotFoundException e) {
                    esSearchHit.setSourceAsMap(Collections.emptyMap());
                }

                // Parse the highlights if any
                try {
                    Map<String, List<String>> highlights = document.read("$.hits.hits[" + hitNum + "].highlight");
                    highlights.forEach(esSearchHit::addHighlightField);
                } catch (PathNotFoundException ignored) {
                    // No highlights
                }

                // Parse the fields if any
                try {
                    Map<String, List<String>> fields = document.read("$.hits.hits[" + hitNum + "].fields");
                    esSearchHit.setStoredFields(fields);
                } catch (PathNotFoundException ignored) {
                    // No stored fields
                }
                // hits.hits[].fields":{"foo.bar":["bar"]}}
                esSearchResponse.addHit(esSearchHit);
            }

            /*
            if (response.getHits() != null) {
                if (response.getAggregations() != null) {
                    for (String name : response.getAggregations().asMap().keySet()) {
                        Terms termsAgg = response.getAggregations().get(name);
                        ESTermsAggregation aggregation = new ESTermsAggregation(name, null);
                        for (Terms.Bucket bucket : termsAgg.getBuckets()) {
                            aggregation.addBucket(new ESTermsAggregation.ESTermsBucket(bucket.getKeyAsString(), bucket.getDocCount()));
                        }
                        esSearchResponse.addAggregation(name, aggregation);
                    }
                }
            }
             */

            return esSearchResponse;
        } catch (NotFoundException e) {
            logger.debug("index {} does not exist.", request.getIndex());
            throw new ElasticsearchClientException("index " + request.getIndex() + " does not exist.");
        }
    }

    private String toElasticsearchQuery(ESQuery query) {
        if (query instanceof ESTermQuery) {
            ESTermQuery esQuery = (ESTermQuery) query;
            return "\"term\": { \"" + esQuery.getField() +  "\": \"" + esQuery.getValue() + "\"}";
        }
        if (query instanceof ESMatchQuery) {
            ESMatchQuery esQuery = (ESMatchQuery) query;
            return "\"match\": { \"" + esQuery.getField() +  "\": \"" + esQuery.getValue() + "\"}";
        }
        if (query instanceof ESPrefixQuery) {
            ESPrefixQuery esQuery = (ESPrefixQuery) query;
            return "\"prefix\": { \"" + esQuery.getField() +  "\": \"" + esQuery.getValue() + "\"}";
        }
        if (query instanceof ESRangeQuery) {
            ESRangeQuery esQuery = (ESRangeQuery) query;
            String localQuery = "\"range\": { \"" + esQuery.getField() + "\": {";
            if (esQuery.getGte() != null) {
                localQuery += "\"gte\": " + esQuery.getGte();
                if (esQuery.getLt() != null) {
                    localQuery += ",";
                }
            }
            if (esQuery.getLt() != null) {
                localQuery += "\"lt\": " + esQuery.getLt();
            }
            localQuery += "}}";
            return localQuery;
        }
        if (query instanceof ESBoolQuery) {
            ESBoolQuery esQuery = (ESBoolQuery) query;
            StringBuilder localQuery = new StringBuilder("\"bool\": { \"must\" : [");
            boolean hasClauses = false;
            for (ESQuery clause : esQuery.getMustClauses()) {
                if (hasClauses) {
                    localQuery.append(",");
                }
                localQuery.append("{");
                localQuery.append(toElasticsearchQuery(clause));
                localQuery.append("}");
                hasClauses = true;
            }
            localQuery.append("]}");
            return localQuery.toString();
        }
        throw new IllegalArgumentException("Query " + query.getClass().getSimpleName() + " not implemented yet");
    }

    @Override
    public void deleteIndex(String index) throws ElasticsearchClientException {
        logger.debug("delete index [{}]", index);
        try {
            String response = httpDelete(index, null);
            Object document = parseJson(response);
            boolean acknowledged = JsonPath.read(document, "$.acknowledged");
            if (!acknowledged) {
                throw new ElasticsearchClientException("Can not remove index " + index + " : " + JsonPath.read(document, "$.error.reason"));
            }
        } catch (NotFoundException e) {
            logger.debug("Index [{}] was not found", index);
        }
    }

    @Override
    public void flush() {
        bulkProcessor.flush();
    }

    @Override
    public String performLowLevelRequest(String method, String endpoint, String jsonEntity) {
        return httpCall(method, endpoint, jsonEntity);
    }

    @Override
    public ESSearchHit get(String index, String id) throws IOException {
        logger.debug("get document [{}/{}]", index, id);
        String response = httpGet(index + "/" + INDEX_TYPE_DOC + "/" + id);

        // Parse the response
        DocumentContext document = parseJsonAsDocumentContext(response);
        ESSearchHit hit = new ESSearchHit();
        hit.setIndex(document.read("$._index"));
        hit.setId(document.read("$._id"));
        hit.setVersion(document.read("$._version"));
        hit.setSource(extractJsonFromPath(document, "$._source"));
        hit.setSourceAsMap(document.read("$._source"));
        return hit;
    }

    @Override
    public boolean exists(String index, String id) throws IOException {
        logger.debug("get document [{}/{}]", index, id);
        String response = httpHead(index + "/" + INDEX_TYPE_DOC + "/" + id);
        // Parse the response
        DocumentContext document = parseJsonAsDocumentContext(response);
        return document.read("$.exists");
    }

    @Override
    public String bulk(String ndjson) {
        logger.debug("bulk a ndjson of {} characters", ndjson.length());

        String response = httpPost("_bulk", ndjson);
        return response;
    }

    private void createIndex(Path jobMappingDir, String elasticsearchVersion, String indexSettingsFile, String indexName) throws Exception {
        try {
            // If needed, we create the new settings for this files index
            String indexSettings = readJsonFile(jobMappingDir, config, elasticsearchVersion, indexSettingsFile);

            createIndex(indexName, true, indexSettings);
        } catch (Exception e) {
            logger.warn("failed to create index [{}], disabling crawler...", indexName);
            throw e;
        }
    }

    String httpHead(String path) {
        return httpCall("HEAD", path, null);
    }

    String httpGet(String path, Map.Entry<String, Object>... params) {
        return httpCall("GET", path, null, params);
    }

    String httpPost(String path, Object data, Map.Entry<String, Object>... params) {
        return httpCall("POST", path, data, params);
    }

    String httpPut(String path, Object data) {
        return httpCall("PUT", path, data);
    }

    private String httpDelete(String path, Object data) {
        return httpCall("DELETE", path, data);
    }

    private String httpCall(String method, String path, Object data, Map.Entry<String, Object>... params) {
        String node = getNode();
        logger.trace("Calling {} {}/{} with params {}", method, node, path == null ? "" : path, params);
        try {
            Invocation.Builder callBuilder = prepareHttpCall(node, path, params);
            if (data == null) {
                String response = callBuilder.method(method, String.class);
                logger.trace("{} {}/{} gives {}", method, node, path == null ? "" : path, response);
                return response;
            }
            String response = callBuilder.method(method, Entity.json(data), String.class);
            logger.trace("{} {}/{} gives {}", method, node, path == null ? "" : path, response);
            return response;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatusInfo().getFamily() == Response.Status.Family.SERVER_ERROR) {
                logger.warn("Error on server side. We need to try another node if possible. {} -> {}",
                        e.getResponse().getStatus(),
                        e.getResponse().getStatusInfo().getReasonPhrase());
                // TODO Retry on the next node if any
                // TODO Remove the node from the list
            } else {
                logger.debug("Error while running {} {}/{}: {}", method, node, path == null ? "" : path, e.getResponse().readEntity(String.class));
            }
            throw e;
        }
    }

    private String getNode() {
        // Choose a node
        int nextNode = nodeNumber.incrementAndGet() % hosts.size();
        return hosts.get(nextNode);
    }

    private Invocation.Builder prepareHttpCall(String node, String path, Map.Entry<String, Object>[] params) {
        WebTarget target = client.target(node);
        if (path != null) {
            target = target.path(path);
        }
        for (Map.Entry<String, Object> param : params) {
            target = target.queryParam(param.getKey(), param.getValue());
        }

        Invocation.Builder builder = target
                .request(MediaType.APPLICATION_JSON)
                .header("Content-Type", "application/json");
        builder.header("User-Agent", USER_AGENT);

        return builder;
    }
}