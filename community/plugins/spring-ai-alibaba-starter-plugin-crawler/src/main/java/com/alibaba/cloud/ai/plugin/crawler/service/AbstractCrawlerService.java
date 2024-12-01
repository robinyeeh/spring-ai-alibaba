package com.alibaba.cloud.ai.plugin.crawler.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

import com.alibaba.cloud.ai.plugin.crawler.exception.CrawlerServiceException;
import com.alibaba.cloud.ai.plugin.crawler.util.UrlValidator;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

public abstract class AbstractCrawlerService implements CrawlerService {

	protected Boolean preCheck(String targetUrl) {

		return !((targetUrl != null && !targetUrl.isEmpty()) || UrlValidator.isValidUrl(targetUrl));
	}

	protected HttpURLConnection initHttpURLConnection(
			String token,
			URL url,
			Map<String, String> optionHeaders,
			String requestBody
	) throws IOException {

		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod(HttpMethod.POST.name());
		connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

		// return by json type.
		connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

		if (Objects.nonNull(optionHeaders) && !optionHeaders.isEmpty()) {
			for (Map.Entry<String, String> entry : optionHeaders.entrySet()) {
				connection.setRequestProperty(entry.getKey(), entry.getValue());
			}
		}

		return this.setRequestBody(connection, requestBody);
	}

	protected String getResponse(HttpURLConnection connection) throws IOException {

		if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
			String errResp = this.getInputStreamAsString(connection.getErrorStream());
			throw new CrawlerServiceException("Request Failed: " + connection.getResponseCode() + " err msg: " + errResp);
		}

		return this.getInputStreamAsString(connection.getInputStream());
	}

	protected String getInputStreamAsString(InputStream inputStream) {

		StringBuilder response = new StringBuilder();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));) {

			String line;
			while ((line = reader.readLine()) != null) {
				response.append(line);
			}
		} catch (IOException e) {
			throw new CrawlerServiceException("Failed to read response: " + e.getMessage());
		}

		return response.toString();
	}

	private HttpURLConnection setRequestBody(HttpURLConnection connection, String requestBody) {

		connection.setDoOutput(true);

		try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());) {
			writer.write(requestBody);
			writer.flush();
		} catch (IOException e) {
			throw new CrawlerServiceException("Failed to write request body: " + e.getMessage());
		}

		return connection;
	}

}
