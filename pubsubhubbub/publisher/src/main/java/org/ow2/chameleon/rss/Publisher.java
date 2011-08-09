package org.ow2.chameleon.rss;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.osgi.framework.BundleContext;
import org.ow2.chameleon.rose.constants.RoseRSSConstants;

public class Publisher {

	private String urlHub;
	private String rssUrl;
	private HttpPost postMethod;
	private HttpClient client;

	/**
	 * Register a topic in hub
	 * 
	 * @param pUrlHub
	 *            - Pubsubhubub url
	 * @param pRssUrl
	 *            - RSS topic url
	 * @param context
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public Publisher(String pUrlHub, String pRssUrl, BundleContext context)
			throws ClientProtocolException, IOException {
		this.urlHub = pUrlHub;
		this.rssUrl = "http://" + InetAddress.getLocalHost().getHostAddress()
				+ ":" + context.getProperty("org.osgi.service.http.port")
				+ pRssUrl;
		client = new DefaultHttpClient();

		postMethod = new HttpPost(this.urlHub);
		postMethod.setHeader("Content-Type",
				"application/x-www-form-urlencoded");

		List<NameValuePair> nvps = new ArrayList<NameValuePair>();
		nvps.add(new BasicNameValuePair(
				RoseRSSConstants.HTTP_POST_PARAMETER_HUB_MODE, "publish"));
		nvps.add(new BasicNameValuePair(
				RoseRSSConstants.HTTP_POST_PARAMETER_RSS_TOPIC_URL, this.rssUrl));

		postMethod.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
		HttpResponse response = client.execute(postMethod);
		if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
			throw new ClientProtocolException("Server didn register a topic");
		}
		//read an empty entity and close a connection
		response.getEntity().getContent().close();
	}

	public void update() throws IOException {
		postMethod = new HttpPost(this.urlHub);
		postMethod.setHeader("Content-Type",
				"application/x-www-form-urlencoded");
		try {
			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
			nvps.add(new BasicNameValuePair(
					RoseRSSConstants.HTTP_POST_PARAMETER_HUB_MODE, "update"));
			nvps.add(new BasicNameValuePair(
					RoseRSSConstants.HTTP_POST_PARAMETER_RSS_TOPIC_URL,
					this.rssUrl));
			postMethod.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
			HttpResponse response = client.execute(postMethod);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_ACCEPTED) {
				response.getEntity().getContent().close();
				throw new ClientProtocolException("Server didnt update");
			}
			//read an empty entity and close a connection
			response.getEntity().getContent().close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	public void unregister() {
		postMethod = new HttpPost(this.urlHub);
		postMethod.setHeader("Content-Type",
				"application/x-www-form-urlencoded");
		try {
			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
			nvps.add(new BasicNameValuePair(
					RoseRSSConstants.HTTP_POST_PARAMETER_HUB_MODE, "unpublish"));
			nvps.add(new BasicNameValuePair(
					RoseRSSConstants.HTTP_POST_PARAMETER_RSS_TOPIC_URL,
					this.rssUrl));
			postMethod.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
			HttpResponse response = client.execute(postMethod);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_ACCEPTED) {
				response.getEntity().getContent().close();
				throw new ClientProtocolException("Server didnt update");
			}
			//read an empty entity and close a connection
			response.getEntity().getContent().close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
}
