package org.ow2.chameleon.rose.pubsubhubbub.hub.internal;

import static org.osgi.framework.FrameworkUtil.createFilter;

import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HTTP_POST_HEADER_TYPE;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HTTP_POST_PARAMETER_HUB_MODE;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HTTP_POST_PARAMETER_RSS_TOPIC_URL;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HTTP_POST_PARAMETER_ENDPOINT_FILTER;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HTTP_POST_PARAMETER_URL_CALLBACK;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.FEED_TITLE_NEW;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.FEED_TITLE_REMOVE;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HUB_UPDATE_ENDPOINT_REMOVED;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HUB_UPDATE_ENDPOINT_ADDED;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HUB_UPDATE_TOPIC_DELETE;
import static org.ow2.chameleon.rose.constants.RoseRSSConstants.HubMode;
import static org.ow2.chameleon.rose.pubsubhubbub.hub.Hub.COMPONENT_NAME;


import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.ipojo.ConfigurationException;
import org.apache.felix.ipojo.Factory;
import org.apache.felix.ipojo.MissingHandlerException;
import org.apache.felix.ipojo.UnacceptableConfiguration;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Property;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.service.log.LogService;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.RemoteConstants;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.ow2.chameleon.json.JSONService;
import org.ow2.chameleon.rose.pubsubhubbub.hub.Hub;
import org.ow2.chameleon.syndication.FeedEntry;
import org.ow2.chameleon.syndication.FeedReader;

/**
 * Component class to work as Hub in Pubsubhubbub technology, specially modified
 * to work with Rose
 * 
 * @author Bartek
 * 
 */
@Component(name = COMPONENT_NAME)
public class HubImpl extends HttpServlet implements Hub {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1526708334275691196L;

	private static final String FEED_READER_FACTORY_FILTER = "(&("
			+ Constants.OBJECTCLASS
			+ "=org.apache.felix.ipojo.Factory)(factory.name=org.ow2.chameleon.syndication.rome.reader))";
	private static final String READER_SERVICE_CLASS = "org.ow2.chameleon.syndication.FeedReader";
	private static final String READER_FILTER_PROPERTY = FeedReader.FEED_URL_PROPERTY;

	@Requires
	private HttpService httpService;

	@Requires
	private JSONService json;

	@Requires(optional = true)
	private LogService logger;

	@Property(name = INSTANCE_PROPERTY_HUB_URL, mandatory = true)
	private String hubServlet;

	// HTTP response status code
	private int responseCode;

	// store instances of RSS reader for different topics
	private Map<String, FeedReader> readers;
	private ServiceTracker feedReaderTracker;
	private ServiceTracker factoryTracker;
	private BundleContext context;
	private Dictionary<String, Object> instanceDictionary;
	private Registrations registrations;

	// client to send notification to subscribers;
	private HttpClient client;

	public HubImpl(BundleContext context) {
		this.context = context;
	}

	@Validate
	void start() {
		try {
			httpService.registerServlet(hubServlet, this, null, null);
			registrations = new Registrations();
			readers = new HashMap<String, FeedReader>();
			client = new DefaultHttpClient(new ThreadSafeClientConnManager());

		} catch (ServletException e) {
			e.printStackTrace();
		} catch (NamespaceException e) {
			e.printStackTrace();
		}
	}

	void stop() {
		httpService.unregister(hubServlet);
	}

	/**
	 * Run trackers for Feed readers and Feed read factories
	 * 
	 * @param rssUrl
	 *            url address to read feeds
	 * @return
	 */
	private boolean createReader(String rssUrl) {
		try {
			new FeedReaderTracker(rssUrl);
			new FactoryTracker(rssUrl);
			return true;
		} catch (InvalidSyntaxException e) {
			logger.log(LogService.LOG_ERROR, "Tracker not stared", e);
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest
	 * , javax.servlet.http.HttpServletResponse)
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		String rssUrl;
		String endpointFilter;
		String callBackUrl;

		// check the content type, must be application/x-www-form-urlencoded
		if ((!(req.getHeader("Content-Type").equals(HTTP_POST_HEADER_TYPE)))
				|| (req.getParameter(HTTP_POST_PARAMETER_HUB_MODE) == null)) {
			resp.setStatus(HttpStatus.SC_BAD_REQUEST);
			return;
		}
		rssUrl = req.getParameter(HTTP_POST_PARAMETER_RSS_TOPIC_URL);
		endpointFilter = req.getParameter(HTTP_POST_PARAMETER_ENDPOINT_FILTER);
		callBackUrl = req.getParameter(HTTP_POST_PARAMETER_URL_CALLBACK);
		// check the hub mode
		switch (HubMode.valueOf(req.getParameter(HTTP_POST_PARAMETER_HUB_MODE))) {
		case publish:

			if ((rssUrl != null) && (createReader(rssUrl))) {
				// register a topic
				registrations.addTopic(rssUrl);
				responseCode = HttpStatus.SC_CREATED;
			} else {
				responseCode = HttpStatus.SC_BAD_REQUEST;
			}
			break;

		case unpublish:

			if (rssUrl != null) {
				// remove a topic
				new SendSubscription(client, rssUrl,
						HUB_UPDATE_ENDPOINT_REMOVED, this,
						HUB_UPDATE_TOPIC_DELETE);

				responseCode = HttpStatus.SC_ACCEPTED;
			} else {
				responseCode = HttpStatus.SC_BAD_REQUEST;
			}
			break;

		case update:
			if ((rssUrl == null) || (readers.get(rssUrl) == null)) {
				responseCode = HttpStatus.SC_BAD_REQUEST;
				break;
			}

			FeedEntry feed = readers.get(rssUrl).getLastEntry();

			try {
				@SuppressWarnings("unchecked")
				EndpointDescription edp = getEndpointDescriptionFromJSON(json
						.fromJSON(feed.content()));
				if (feed.title().equals(FEED_TITLE_NEW)) {
					registrations.addEndpoint(rssUrl, edp);
					new SendSubscription(client, edp,
							HUB_UPDATE_ENDPOINT_ADDED, this);
				} else if (feed.title().equals(FEED_TITLE_REMOVE)) {
					registrations.removeEndpoint(rssUrl, edp);
					new SendSubscription(client, edp,
							HUB_UPDATE_ENDPOINT_REMOVED, this);
				}

				responseCode = HttpStatus.SC_ACCEPTED;
			} catch (ParseException e) {
				responseCode = HttpStatus.SC_BAD_REQUEST;
				e.printStackTrace();
			}
			break;

		case subscribe:
			if ((endpointFilter == null) || (callBackUrl == null)) {
				responseCode = HttpStatus.SC_BAD_REQUEST;
			} else {
				registrations.addSubscrition(callBackUrl, endpointFilter);
				responseCode = HttpStatus.SC_CREATED;
				// check if already register an endpoint which matches the
				// filter

				new SendSubscription(client, callBackUrl,
						HUB_UPDATE_ENDPOINT_ADDED, this);
			}

			break;

		case unsubscribe:
			if (callBackUrl == null) {
				responseCode = HttpStatus.SC_BAD_REQUEST;
				break;
			}
			registrations.removeSubscribtion(callBackUrl);
			responseCode = HttpStatus.SC_ACCEPTED;
			break;

		case getAllEndpoints:
			// for Rose Pubsuhhubbub webconsole purpose
			resp.setContentType("text/html");
			for (EndpointDescription endpoint : registrations.getAllEndpoints()) {
				resp.getWriter().append(endpoint.toString() + "<br><br>");
			}
			responseCode = HttpStatus.SC_ACCEPTED;
			break;

		// hub.mode not found
		default:
			responseCode = HttpStatus.SC_BAD_REQUEST;
			break;
		}

		resp.setStatus(responseCode);
	}

	/**
	 * Creates an EndpointDescription from JSON map, checks for errors which
	 * occurs after parsing from string to JSON
	 * 
	 * @param map
	 *            endpoint description map property
	 * @return proper endpoint description
	 */
	@SuppressWarnings("unchecked")
	private EndpointDescription getEndpointDescriptionFromJSON(
			Map<String, Object> map) {

		if (map.get(Constants.OBJECTCLASS) instanceof ArrayList<?>) {
			map.put(Constants.OBJECTCLASS, ((ArrayList<String>) map
					.get(Constants.OBJECTCLASS)).toArray(new String[0]));
		}

		if (map.get(RemoteConstants.ENDPOINT_SERVICE_ID) instanceof Integer) {
			Integer id = (Integer) map
					.get((RemoteConstants.ENDPOINT_SERVICE_ID));
			map.put(RemoteConstants.ENDPOINT_SERVICE_ID, id.longValue());
		}
		return new EndpointDescription(map);
	}

	public JSONService json() {
		return json;
	}

	public Registrations registrations() {
		return registrations;
	}

	/**
	 * Tracker for Feed reader
	 * 
	 * @author Bartek
	 * 
	 */
	private class FeedReaderTracker implements ServiceTrackerCustomizer {
		private String rss_url;

		/**
		 * Set a filter properties and run feed reader tracker
		 * 
		 * @param rss_url
		 *            url to RSS
		 * @throws InvalidSyntaxException
		 */
		public FeedReaderTracker(String rss_url) throws InvalidSyntaxException {

			this.rss_url = rss_url;

			String readerFilter = ("(&(" + Constants.OBJECTCLASS + "="
					+ READER_SERVICE_CLASS + ")(" + READER_FILTER_PROPERTY
					+ "=" + this.rss_url + "))");
			feedReaderTracker = new ServiceTracker(context,
					createFilter(readerFilter), this);
			feedReaderTracker.open();
		}

		public Object addingService(ServiceReference reference) {
			FeedReader reader = (FeedReader) context.getService(reference);
			readers.put(this.rss_url, reader);
			return reader;
		}

		public void modifiedService(ServiceReference reference, Object service) {

		}

		public void removedService(ServiceReference reference, Object service) {
			readers.remove(this.rss_url);

		}
	}

	/**
	 * Tracker for Feed reader factory
	 * 
	 * @author Bartek
	 * 
	 */
	private class FactoryTracker implements ServiceTrackerCustomizer {
		private String rss_url;

		/**
		 * Set instance properties and run a tracker
		 * 
		 * @param rss_url
		 *            Url to RSS
		 * @throws InvalidSyntaxException
		 */
		public FactoryTracker(String rss_url) throws InvalidSyntaxException {

			this.rss_url = rss_url;

			instanceDictionary = new Hashtable<String, Object>();
			instanceDictionary.put("feed.url", this.rss_url);
			instanceDictionary.put("feed.period", 1);
			factoryTracker = new ServiceTracker(context,
					createFilter(FEED_READER_FACTORY_FILTER), this);
			factoryTracker.open();
		}

		public Object addingService(ServiceReference reference) {
			Factory factory = (Factory) context.getService(reference);
			try {
				if (!(readers.containsKey(this.rss_url))) {
					return factory.createComponentInstance(instanceDictionary);
				}
			} catch (UnacceptableConfiguration e) {
				e.printStackTrace();
			} catch (MissingHandlerException e) {
				e.printStackTrace();
			} catch (ConfigurationException e) {
				e.printStackTrace();
			}
			return readers.get(rss_url);
		}

		public void modifiedService(ServiceReference reference, Object service) {
		}

		public void removedService(ServiceReference reference, Object service) {
			readers.remove(this.rss_url);
		}
	}

}
