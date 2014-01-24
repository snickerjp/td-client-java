package com.treasure_data.newclient.http;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;

import com.treasure_data.newclient.Configuration;
import com.treasure_data.newclient.TreasureDataClientException;
import com.treasure_data.newclient.TreasureDataServiceException;

public class TreasureDataHttpClient implements Closeable {

    private static final Logger LOG = Logger.getLogger(TreasureDataHttpClient.class.getName());

    private Configuration conf;
    private HttpClient httpClient;

    private HttpClientFactory httpClientFactory = new HttpClientFactory();
    private HttpContentFactory httpContentFactory = new HttpContentFactory();

    public TreasureDataHttpClient(Configuration conf) throws TreasureDataClientException {
        this.conf = conf;
        try {
            httpClient = httpClientFactory.createHttpClient(conf);
            httpClient.start();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "cannot start http client", e);
            throw new TreasureDataClientException(e);
        }
    }

    public <M, REQ> M execute(
            Request<REQ> request,
            ResponseHandler<M> responseHandler,
            ResponseHandler<TreasureDataServiceException> errorResponseHandler,
            ExecutionContext context)
                    throws TreasureDataClientException, TreasureDataServiceException {
        // null check
        if (request == null || responseHandler == null
                || errorResponseHandler == null || context == null) {
            RuntimeException e = new NullPointerException(
                    "Internal Error: some of arguments are null.");
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw e;
        }

        int retryCount = 0;
        TreasureDataServiceException exception = null;

        // make a copy of the original request parameters
        Map<String, String> originalParameters = new HashMap<String, String>();
        originalParameters.putAll(request.getParameters());

        // make a copy of the original request headers
        Map<String, String> originalHeaders = new HashMap<String, String>();
        originalHeaders.putAll(request.getHeaders());

        while (true) {
            if (retryCount > 0) {
                request.setParameters(originalParameters);
                request.setHeaders(originalHeaders);
            }

            ContentExchange exchange = null;

            try {
                // sign the rquest if a signer was provided
                if (context.getSinger() != null && context.getCredentials() != null) {
                    context.getSinger().sign(request, context.getCredentials());
                }

                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("Send the request %s (retryCount=%d)",
                            request, retryCount));
                }

                exchange = httpContentFactory.createHttpContent(request, conf, context);

                // pause exponentially
                if (retryCount > 0) {
                    pauseExponentially(retryCount);
                }
                
                exception = null;
                httpClient.send(exchange);
                try {
                    exchange.waitForDone();
                } catch (InterruptedException e) { // TODO FIXME
                    throw new TreasureDataClientException("interrupted");
                }

//                if (isRequestSuccessful(exchange.getResponseStatus())) {
//                    return handleResponse(request, responseHandler);
//                } else {
//                    exception = handleErrorResponse(request, errorResponseHandler);
//
//                    if (!shouldRetry(httpRequest, exception, retryCount)) {
//                        throw exception;
//                    }
//
//                    resetRequestAfterError(request, exception);
//                }
            } catch (IOException e) {
                LOG.log(Level.INFO, "Unable to execute HTTP request: " + e.getMessage(), e);

//                if (!shouldRetry(httpRequest, e, retryCount)) {
//                    TreasureDataClientException ex = new TreasureDataClientException(
//                            "Unable to execute HTTP request: " + e.getMessage(), e);
//                    LOG.log(Level.SEVERE, ex.getMessage(), ex);
//                    throw ex;
//                }

                resetRequestAfterError(request, e);
            } finally {
                retryCount++;
//
//                try {
//                    exchange.get
//                    httpResponse.getEntity().getContent().close();
//                } catch (Throwable t) {
//                    LOG.log(Level.FINE, "during close method", t);
//                }
            }
        }
    }

    private boolean isRequestSuccessful(int status) {
        return status / 100 == 2; // 200 / 100
    }

    private void pauseExponentially(final int retryCount) throws TreasureDataClientException {
        long scaleFactor = 300;
        long delay = (long) (Math.pow(2, retryCount) * scaleFactor);
        delay = Math.min(delay, Configuration.MAX_BACKOFF_IN_MILLISECONDS);

        LOG.fine(String.format("will retry in %dms, attempt number: %d",
                delay, retryCount));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new TreasureDataClientException(e.getMessage(), e);
        }
    }

//    private <M, REQ> M handleResponse(
//            Request<REQ> request,
//            HttpResponseHandler<TreasureDataServiceResponse<M>> responseHandler,
//            HttpRequestBase httpRequest,
//            org.apache.http.HttpResponse apacheHttpResponse,
//            ExecutionContext context)
//                    throws IOException, TreasureDataClientException {
//        HttpResponse httpResponse = createResponse(request, httpRequest, apacheHttpResponse);
//        try {
//            CountingInputStream countingInputStream =
//                    new CountingInputStream(httpResponse.getContent());
//            httpResponse.setContent(countingInputStream);
//            TreasureDataServiceResponse<M> response = responseHandler.handle(httpResponse);
//            return response.getResult();
//        } catch (Exception e) {
//            String errorMessage = "Unable to unmarshall response (" + e.getMessage() + ")";
//            throw new TreasureDataClientException(errorMessage, e);
//        }
//    }
//
//    private <REQ> TreasureDataServiceException handleErrorResponse(
//            Request<REQ> request,
//            HttpResponseHandler<TreasureDataServiceResponse<TreasureDataServiceException>> errorResponseHandler,
//            HttpRequestBase httpRequest,
//            org.apache.http.HttpResponse apacheHttpResponse,
//            ExecutionContext context)
//                    throws IOException, TreasureDataServiceException {
//        HttpResponse httpResponse = createResponse(request, httpRequest, apacheHttpResponse);
//        StatusLine stat = apacheHttpResponse.getStatusLine();
//        int status = stat.getStatusCode();
//
//        TreasureDataServiceException exception = null;
//        try {
//            exception = errorResponseHandler.handle(httpResponse).getResult();
//            exception.setStatusCode(status);
//            exception.setErrorCode(stat.getReasonPhrase());
//            LOG.fine("Received error response: " + exception.toString());
//        } catch (Exception e) {
//            // If the errorResponseHandler doesn't work, then check for error
//            // responses that don't have any content
//            if (status == 413) {
//                exception = new TreasureDataServiceException("Request entity too large");
//                exception.setStatusCode(413);
//                exception.setErrorCode("Request entity too large");
//            } else if (status == 503 && "Service Unavailable".equalsIgnoreCase(apacheHttpResponse.getStatusLine().getReasonPhrase())) {
//                exception = new TreasureDataServiceException("Service unavailable");
//                exception.setStatusCode(503);
//                exception.setErrorCode("Service unavailable");
//            } else {
//                String errorMessage = "Unable to unmarshall error response (" + e.getMessage() + ")";
//                throw new TreasureDataServiceException(errorMessage, e);
//            }
//        }
//
//        exception.setStatusCode(status);
//        exception.fillInStackTrace();
//        return exception;
//    }
//
//    private <REQ> HttpResponse createResponse(
//            Request<REQ> request,
//            HttpRequestBase method,
//            org.apache.http.HttpResponse apacheHttpResponse) throws IOException {
//        HttpResponse httpResponse = new HttpResponse(request, method);
//
//        if (apacheHttpResponse.getEntity() != null) {
//            httpResponse.setContent(apacheHttpResponse.getEntity().getContent());
//        }
//
//        httpResponse.setStatusCode(apacheHttpResponse.getStatusLine().getStatusCode());
//        httpResponse.setStatusText(apacheHttpResponse.getStatusLine().getReasonPhrase());
//        for (Header header : apacheHttpResponse.getAllHeaders()) {
//            httpResponse.addHeader(header.getName(), header.getValue());
//        }
//
//        return httpResponse;
//    }

    public void close() throws IOException {
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "failed to stop http client", e);
                throw new IOException(e);
            }
        }
    }

    private boolean shouldRetry(HttpMethods method, ContentExchange exchange,
            Exception exception, int retries) {
        if (retries >= 10) {
            return false;
        }

        if (exception instanceof IOException) {
            LOG.fine("Retrying on " + exception.getClass().getName()
                        + ": " + exception.getMessage());
            return true;
        }

        if (exception instanceof TreasureDataServiceException) {
            TreasureDataServiceException ase = (TreasureDataServiceException)exception;

            /*
             * For 500 internal server errors and 503 service
             * unavailable errors, we want to retry, but we need to use
             * an exponential back-off strategy so that we don't overload
             * a server with a flood of retries. If we've surpassed our
             * retry limit we handle the error response as a non-retryable
             * error and go ahead and throw it back to the user as an exception.
             */
            if (exchange.getStatus() == 500 || exchange.getStatus() == 503) {
                return true;
            }
        }

        return false;
    }

    private void resetRequestAfterError(Request<?> request, Exception cause) throws TreasureDataClientException {
        if (request.getContent() != null && request.getContent().markSupported())  {
            try {
                request.getContent().reset();
            } catch (IOException e) {
                // This exception comes from being unable to reset the input stream,
                // so throw the original, more meaningful exception
                throw new TreasureDataClientException(
                        "Encountered an exception and couldn't reset the stream to retry", cause);
            }
        }
    }

}