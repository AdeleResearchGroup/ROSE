package org.ow2.chameleon.rss;

import static org.osgi.framework.FrameworkUtil.createFilter;
import static org.osgi.service.log.LogService.LOG_INFO;
import static org.osgi.service.log.LogService.LOG_WARNING;

import java.io.IOException;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.apache.felix.ipojo.ConfigurationException;
import org.apache.felix.ipojo.Factory;
import org.apache.felix.ipojo.MissingHandlerException;
import org.apache.felix.ipojo.UnacceptableConfiguration;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Property;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.log.LogService;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.osgi.service.remoteserviceadmin.EndpointListener;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.ow2.chameleon.json.JSONService;
import org.ow2.chameleon.syndication.FeedEntry;
import org.ow2.chameleon.syndication.FeedWriter;
import org.ow2.chameleon.rose.constants.RoseRSSConstants;

@Component(name = "Rose_Pubsubhubbub.publisher")
public class EndpointTrackerRSS implements EndpointListener {

	private static final String SERVLET_FACTORY_FILTER = "(&("
			+ Constants.OBJECTCLASS
			+ "=org.apache.felix.ipojo.Factory)(factory.name=org.ow2.chameleon.syndication.rome.servlet))";

	private static final String WRITER_SERVICE_CLASS = "org.ow2.chameleon.syndication.FeedWriter";
	private static final String WRITER_FILER_PROPERTY = "org.ow2.chameleon.syndication.feed.url";

	@Property(mandatory = true, name = "rss.url")
	private String rss_url;
	
	@Property(name="hub.url")
	private String hubUrl;

	@Requires(optional = true)
	LogService logger;

	@Requires
	private JSONService json;

	@Requires(optional = true)
	private EventAdmin eventAdmin;

	
	private FeedWriter writer;
	private BundleContext context;
	private ServiceRegistration endpointListener;
	private ServiceTracker factoryTracker;
	private ServiceTracker feedWriterTracker;
	private Dictionary<String, Object> instanceDictionary;
	private boolean running = false; // don`t update RSS after stop
	private Map<String, Object> eventProperties;
	private Event event;
	private Publisher subscriber;
	

	public EndpointTrackerRSS(BundleContext context) {
		super();
		this.context = context;
	}

	@Validate
	public void start() {
		running = true;

		// tracking an FeedWriter and Feed servlet factory
		startTracking();
		
		// Configure an event properties
		eventProperties = new HashMap<String, Object>();
		eventProperties.put("Author", RoseRSSConstants.FEED_AUTHOR);
		eventProperties.put("Feed url", rss_url);


		// TODO pubsubhubbub
		try {
			subscriber = new Publisher(hubUrl, rss_url, context);
		} catch (IOException e) {
			e.printStackTrace();

		}

		// Register an EndpointListener
		endpointListener = context.registerService(
				EndpointListener.class.getName(), this, null);
		logger.log(LOG_INFO, "EndpointTrackerRSS sucessfully started");


	}

	@Invalidate
	public void stop() {
		running = false;
		endpointListener.unregister();
		if (factoryTracker != null) {
			factoryTracker.close();
		}

		if (feedWriterTracker != null) {
			feedWriterTracker.close();
		}
		subscriber.unregister();
		
	}

	public void endpointAdded(EndpointDescription endp, String filter) {
		FeedEntry feed = writer.createFeedEntry();
		feed.title(RoseRSSConstants.FEED_TITLE_NEW);
		feed.content(json.toJSON(endp.getProperties()));
		feed.url(rss_url);
		try {
			// publish a feed
			writer.addEntry(feed);
			// sending an event
			sendEndpointEvent(RoseRSSConstants.FEED_TITLE_NEW,
					json.toJSON(endp.getProperties()));
			
			// TODO pubsubhubbub
			subscriber.update();
		} catch (IOException e) {
			logger.log(LOG_WARNING, "Error in updateing a feed", e);
		}
		catch (Exception e) {
			logger.log(LOG_WARNING, "Error in sending a feed", e);
		}
	}

	public void endpointRemoved(EndpointDescription endp, String arg1) {
		if (running) {
			FeedEntry feed = writer.createFeedEntry();
			feed.title(RoseRSSConstants.FEED_TITLE_REMOVE);
			feed.content(json.toJSON(endp.getProperties()));
			try {
				// publish a feed
				writer.addEntry(feed);
				// sending an event
				sendEndpointEvent(RoseRSSConstants.FEED_TITLE_REMOVE,
						json.toJSON(endp.getProperties()));
				
				// TODO pubsubhubbub
				subscriber.update();
			} catch (IOException e) {
				logger.log(LOG_WARNING, "Error in updateing a feed", e);
			}
			catch (Exception e) {
				logger.log(LOG_WARNING, "Error in sending a feed", e);
			}
		}
	}

	private void sendEndpointEvent(String title, String content) {
		// check if eventAdmin service is available
		if (eventAdmin != null) {
			// prepare event properties
			eventProperties.put("Title", title);
			eventProperties.put("Content", content);
			eventProperties.put(EventConstants.TIMESTAMP,
					System.currentTimeMillis());

			// create and send a event
			event = new Event(RoseRSSConstants.RSS_EVENT_TOPIC, eventProperties);
			eventAdmin.sendEvent(event);
		}
	}

	private void startTracking() {
		try {
			new FeedWriterTracker();
			new FactoryTracker();
		} catch (InvalidSyntaxException e) {
			logger.log(LogService.LOG_ERROR, "Tracker not stared", e);
		}
	}

	private class FactoryTracker implements ServiceTrackerCustomizer {

		public FactoryTracker() throws InvalidSyntaxException {

			instanceDictionary = new Hashtable<String, Object>();
			instanceDictionary.put("org.ow2.chameleon.syndication.feed.title",
					"RoseRss");
			instanceDictionary
					.put("org.ow2.chameleon.syndication.feed.servlet.alias",
							rss_url);

			factoryTracker = new ServiceTracker(context,
					createFilter(SERVLET_FACTORY_FILTER), this);
			factoryTracker.open();
		}

		public Object addingService(ServiceReference reference) {

			Factory factory = (Factory) context.getService(reference);
			try {
				if (writer == null) {
					return factory.createComponentInstance(instanceDictionary);
				}
			} catch (UnacceptableConfiguration e) {
				e.printStackTrace();
			} catch (MissingHandlerException e) {
				e.printStackTrace();
			} catch (ConfigurationException e) {
				e.printStackTrace();
			}
			return null;
		}

		public void modifiedService(ServiceReference reference, Object service) {
		}

		public void removedService(ServiceReference reference, Object service) {
			writer = null;
		}
	}

	private class FeedWriterTracker implements ServiceTrackerCustomizer {

		public FeedWriterTracker() throws InvalidSyntaxException {

			String writerFilter = ("(&(" + Constants.OBJECTCLASS + "="
					+ WRITER_SERVICE_CLASS + ")(" + WRITER_FILER_PROPERTY
					+ "=http:*" + rss_url + "))");
			feedWriterTracker = new ServiceTracker(context,
					createFilter(writerFilter), this);
			feedWriterTracker.open();

		}

		public Object addingService(ServiceReference reference) {
			writer = (FeedWriter) context.getService(reference);
			return writer;
		}

		public void modifiedService(ServiceReference reference, Object service) {
			writer = (FeedWriter) context.getService(reference);
		}

		public void removedService(ServiceReference reference, Object service) {
			context.ungetService(reference);
			writer = null;
		}

	}

}
