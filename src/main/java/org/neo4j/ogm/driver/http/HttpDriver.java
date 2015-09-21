package org.neo4j.ogm.driver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.neo4j.ogm.authentication.Neo4jCredentials;
import org.neo4j.ogm.driver.Driver;
import org.neo4j.ogm.driver.config.DriverConfig;
import org.neo4j.ogm.mapper.MappingContext;
import org.neo4j.ogm.session.request.RequestHandler;
import org.neo4j.ogm.session.response.Neo4jResponse;
import org.neo4j.ogm.session.result.ErrorsException;
import org.neo4j.ogm.session.result.ResultProcessingException;
import org.neo4j.ogm.session.transaction.Transaction;
import org.neo4j.ogm.session.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author vince
 */
public final class HttpDriver implements Driver<String> {

    private static final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final Logger logger = LoggerFactory.getLogger(HttpDriver.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private DriverConfig driverConfig;
    private String url;

    public HttpDriver() {
        configure(new DriverConfig("driver.properties.http"));
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            logger.warn("Unexpected Exception when closing http client transport: ", e);
        }
    }

    @Override
    public RequestHandler requestHandler() {
        return new HttpRequest(mapper, this);
    }

    @Override
    public void configure(DriverConfig config) {
        this.driverConfig = config;
    }

    @Override
    public Object getConfig(String key) {
        return driverConfig.getConfig(key);
    }

    @Override
    public Transaction openTransaction(MappingContext context, TransactionManager txManager, boolean autoCommit) {

        String url = autoCommit? autoCommitUrl() : newTransactionUrl();
        Transaction tx = new HttpTransaction(context, txManager, autoCommit, this, url); // do we need URL?

        this.url = tx.url();
        return tx;
    }

    @Override
    public Neo4jResponse<String> execute(String cypher, Map<String, Object> parameters) {
        throw new RuntimeException("Not implemented");
    }


    @Override
    public Neo4jResponse<String> execute(String cypher) {

        HttpResponse jsonResponse = null;

        try {
            String url = this.url;

            assert(url != null);

            logger.debug("POST " + url + ", request: " + cypher);

            HttpPost request = new HttpPost(url);
            HttpEntity entity = new StringEntity(cypher,"UTF-8");

            request.setHeader(new BasicHeader(HTTP.CONTENT_TYPE,"application/json;charset=UTF-8"));
            request.setHeader(new BasicHeader("Accept", "application/json;charset=UTF-8"));

            // http://tools.ietf.org/html/rfc7231#section-5.5.3
            request.setHeader(new BasicHeader("User-Agent", "neo4j-ogm.java/1.0"));

            HttpAuthorization.authorize(request, (Neo4jCredentials) driverConfig.getConfig("credentials"));

            request.setEntity(entity);

            CloseableHttpResponse response = httpClient.execute(request);

            StatusLine statusLine = response.getStatusLine();
            HttpEntity responseEntity = response.getEntity();

            if (statusLine.getStatusCode() >= 300) {
                throw new HttpResponseException(
                        statusLine.getStatusCode(),
                        statusLine.getReasonPhrase());
            }
            if (responseEntity == null) {
                throw new ClientProtocolException("Response contains no content");
            }

            logger.debug("Response is OK, creating response handler");
            jsonResponse = new HttpResponse(response);
            return jsonResponse;

        }
        // the primary exception handler, will ensure all resources are properly closed
        catch (Exception e) {
            logger.warn("Caught response exception: " + e.getLocalizedMessage());
            if (jsonResponse != null) {
                jsonResponse.close();
            }
            throw new ResultProcessingException("Failed to execute request: " + cypher, e);
        }
    }

    CloseableHttpResponse executeHttpRequest(HttpRequestBase request) {

        try {

            request.setHeader(new BasicHeader("Accept", "application/json;charset=UTF-8"));


            HttpAuthorization.authorize(request, (Neo4jCredentials) driverConfig.getConfig("credentials"));

            CloseableHttpResponse response = httpClient.execute(request);
            StatusLine statusLine = response.getStatusLine();

            logger.debug("Status code: " + statusLine.getStatusCode());
            if (statusLine.getStatusCode() >= 300) {
                throw new HttpResponseException(
                        statusLine.getStatusCode(),
                        statusLine.getReasonPhrase());
            }
            // close the content stream/release the connection
            HttpEntity responseEntity = response.getEntity();

            if (responseEntity != null) {
                String responseText = EntityUtils.toString(responseEntity);
                logger.debug(responseText);
                EntityUtils.consume(responseEntity);
                if (responseText.contains("\"errors\":[{") || responseText.contains("\"errors\": [{")) {
                    throw new ErrorsException(responseText);
                }
            }
            return response;
        }

        catch (Exception e) {
            throw new ResultProcessingException("Failed to execute request: ", e);
        }

        finally {
            request.releaseConnection();
        }
    }

    private String newTransactionUrl() {
        String url = transactionEndpoint((String) driverConfig.getConfig("server"));
        logger.debug("POST " + url);
        HttpPost request = new HttpPost(url);
        request.setHeader(new BasicHeader(HTTP.CONTENT_TYPE, "application/json;charset=UTF-8"));
        org.apache.http.HttpResponse response = executeHttpRequest(request);
        Header location = response.getHeaders("Location")[0];
        return location.getValue();
    }

    private String autoCommitUrl() {
        return transactionEndpoint((String) driverConfig.getConfig("server")).concat("/commit");
    }

    private String transactionEndpoint(String server) {
        if (server == null) {
            return server;
        }
        String url = server;

        if (!server.endsWith("/")) {
            url += "/";
        }
        return url + "db/data/transaction";
    }

}
