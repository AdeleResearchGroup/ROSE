package org.ow2.chameleon.rose.jsonrpc;

import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.swissbox.tinybundles.core.TinyBundles.newBundle;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.ENDPOINT_ID;
import static org.osgi.service.remoteserviceadmin.RemoteConstants.SERVICE_IMPORTED_CONFIGS;
import static org.ow2.chameleon.rose.ImporterService.ENDPOINT_CONFIG_PREFIX;

import java.util.HashMap;
import java.util.Map;

import org.jabsorb.JSONRPCBridge;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.Constants;
import org.osgi.service.remoteserviceadmin.EndpointDescription;
import org.ow2.chameleon.rose.ImporterService;
import org.ow2.chameleon.rose.testing.ImporterComponentAbstractTest;

/**
 * Integration test for the jabsorb-endpoint-creator component.
 * @author barjo
 */
@RunWith(JUnit4TestRunner.class)
public class ImporterTest extends ImporterComponentAbstractTest {
    private static final String FILTER="("+ENDPOINT_CONFIG_PREFIX+"=jsonrpc)";
    private static final String PROP_JABSORB_URL="org.jabsorb.url";
    protected static final String SERVLETNAME ="/JSONRPC";
    

    @Configuration
	public static Option[] endpointCreatorBundle() {
		return CoreOptions.options(CoreOptions.provision(
                mavenBundle().groupId("com.sun.grizzly.osgi").artifactId("grizzly-httpservice-bundle").versionAsInProject(),
				mavenBundle().groupId("org.json").artifactId("org.ow2.chameleon.commons.json").versionAsInProject(),
                mavenBundle().groupId("org.apache.servicemix.bundles").artifactId("org.apache.servicemix.bundles.commons-httpclient").versionAsInProject(),
                mavenBundle().groupId("org.apache.servicemix.bundles").artifactId("org.apache.servicemix.bundles.commons-codec").versionAsInProject(),
                mavenBundle().groupId("commons-logging").artifactId("org.ow2.chameleon.commons.logging").versionAsInProject(),
                mavenBundle().groupId("org.jabsorb").artifactId("org.ow2.chameleon.commons.jabsorb").versionAsInProject(),
                mavenBundle().groupId("org.ow2.chameleon.rose.jsonrpc").artifactId("jabsorb-importer").versionAsInProject()
				),
				CoreOptions.provision(
						newBundle().add(MyActivator.class)
						.set(Constants.BUNDLE_ACTIVATOR, MyActivator.class.getName())
						.set(Constants.IMPORT_PACKAGE, "org.osgi.service.http,javax.servlet,org.osgi.util.tracker,org.osgi.framework,org.jabsorb,org.junit")
						.set(Constants.BUNDLE_SYMBOLICNAME,"My dummy bundle").build()
				));
	}
    
    @Test
    @Override
    public void testImportService() {
    	super.testImportService();
    }
    
    @Override
    protected ImporterService getImporterService(){
    	return rose.getServiceObject(ImporterService.class, FILTER);
    }


	@Override
	protected <T> EndpointDescription createEndpoint(String endpointId,
			Class<T> klass, T object) {
		//A JsonServlet must be registered
        JSONRPCBridge jsonbridge = JSONRPCBridge.getGlobalBridge();
		
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(PROP_JABSORB_URL, "http://localhost:"+HTTP_PORT+SERVLETNAME);
        props.put(ENDPOINT_ID, endpointId);
        props.put(OBJECTCLASS, new String[]{klass.getName()});
        props.put(SERVICE_IMPORTED_CONFIGS, "jsonrpc");
        
        EndpointDescription desc = new EndpointDescription(props);
        
        jsonbridge.registerObject(desc.getId(), object, klass);
        
		return desc;
	}
	
}

