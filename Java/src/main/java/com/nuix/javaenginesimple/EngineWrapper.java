package com.nuix.javaenginesimple;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.collections4.IterableUtils;
import org.apache.log4j.Logger;

import nuix.Utilities;
import nuix.engine.AvailableLicence;
import nuix.engine.CertificateTrustCallback;
import nuix.engine.CertificateTrustCallbackInfo;
import nuix.engine.CredentialsCallback;
import nuix.engine.CredentialsCallbackInfo;
import nuix.engine.Engine;
import nuix.engine.GlobalContainer;
import nuix.engine.Licensor;

/***
 * This class provides convenience methods for getting a license and standing up the global container
 * and engine instance.  Caller must provide base directory of engine release they are running against.
 * 
 * Once a license has been obtained, this will log a listing of features available on the license as well
 * as information about the presence of third part dependencies that Nuix makes use of.
 * @author Jason Wells
 *
 */
public class EngineWrapper implements AutoCloseable {
	// Obtain a logger instance for this class
	private final static Logger logger = Logger.getLogger("EngineWrapper");
	
	private static GlobalContainer container = null;
	
	private File nuixBaseDirectory = null;
	private Engine engine = null;
	
	private Thread shutdownHook = null;
	
	// We will use this while iterating licenses to determine which one to acquire.
	// By default this filter accepts any license.
	private LicenseFilter licenseFilter = new LicenseFilter();
	
	private CertificateTrustCallback certificateTrustCallback = null;

	/***
	 * Creates a new instance running against the specified engine release.
	 * @param nuixBaseDirectory Base directory of the target engine release.
	 */
	public EngineWrapper(File nuixBaseDirectory){
		this.nuixBaseDirectory = nuixBaseDirectory;
		
		// We want to set the System property "nuix.libdir" so that when the engine spins up worker processes,
		// that it can specify the "lib" directory properly to the workers.  Without this the engine will instead
		// attempt to specify an absolute path to each of the JARs, which can cause the command used to start the
		// worker process in being longer than the maximum length allowed by the OS.
//		File engineLibDirectory = new File(nuixBaseDirectory,"lib");
//		logger.info(String.format("Setting 'nuix.libdir' to: %s", engineLibDirectory.getAbsolutePath()));
//		System.setProperty("nuix.libdir", engineLibDirectory.getAbsolutePath());
		
		// Whenever we create an instance of the engine to hand over to the user, we will register
		// our shutdown hook to close this instance.  This helps to ensure that the license is released
		// in some scenarios where the finally block may not execute, such as code calling System.exit before
		// returning from user provided Consumer<Utilities>.
		shutdownHook = new Thread(() -> {
			try { close(); } catch (Exception e)
			{ logger.error("Error in shutdown hook",e); }
		});
	}
	
	/***
	 * Creates a new instance running against the specified engine release.
	 * @param nuixBaseDirectory Base directory of the target engine release.
	 */
	public EngineWrapper(String nuixBaseDirectory){
		this(new File(nuixBaseDirectory));
	}

	public String getEngineVersion() {
		return engine.getVersion();
	}
	/***
	 * Attempts to obtain a dongle based license.  If and when a dongle based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown. 
	 */
	public void withDongleLicense(Consumer<Utilities> consumer) throws Exception{
		// Make sure we have a valid path to Nuix distributable
		if(nuixBaseDirectory == null){
			throw new Exception(String.format("Value provided for nuixBaseDirectory is invalid, value provided is: %s",nuixBaseDirectory));
		}
				
		logger.info("Creating GlobalContainer...");
		try {
			// We start by getting our hands on a GlobalContainer instance.  There can only
			// be one of these per Java process.
			if(container == null){
				container = nuix.engine.GlobalContainerFactory.newContainer();	
			}
			
			logger.info("Initializing engine....");
			try {
				// Next we build an Engine instance from the GlobalContainer
				configureAndBuildEngine();
				
				logger.info("Attempting to obtain a license...");
				try {
					Map<String,Object> licenseOptions = new HashMap<String,Object>();
					licenseOptions.put("sources","dongle");
					
					boolean licenseObtained = obtainLicense(licenseOptions);
					if(licenseObtained){
						Utilities utilities = engine.getUtilities();
						ThirdPartyDependencyChecker.logAllDependencyInfo(utilities);
						
						// Setup our shutdown hook in case user terminates before consumer returns
						logger.info("Adding shutdown hook for EngineWrapper::close");
						Runtime.getRuntime().addShutdownHook(shutdownHook);
						
						logger.info("License was obtained, providing Utilities object to consumer...");
						consumer.accept(utilities);
					}
				} catch (Exception licenseException) {
					logger.error("Error obtaining license",licenseException);
					throw licenseException;
				}
			} catch (Exception engineException) {
				logger.error("Error while creating Engine instance",engineException);
				throw engineException;
			} finally {
				close();
			}
		} catch (Exception globalContainerException) {
			logger.error("Error while creating GlobalContainer",globalContainerException);
			throw globalContainerException;
		} finally {
			if(container != null){
				logger.info("Closing GlobalContainer...");
				container.close();
			}
		}
	}
	
	/***
	 * Attempts to obtain a server based license.  If and when a server based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param userName Username to provide license server
	 * @param password Password to provide license server
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown.
	 */
	public void withServerLicense(final String userName, final String password, Consumer<Utilities> consumer) throws Exception{
		withServerLicense(null, userName, password, consumer);
	}

	/***
	 * Attempts to obtain a server based license.  If and when a server based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param server Location of license server (IP or hostname)
	 * @param userName Username to provide license server
	 * @param password Password to provide license server
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown.
	 */
	public void withServerLicense(final String server, final String userName, final String password, Consumer<Utilities> consumer) throws Exception{
		if(server != null) {
			System.getProperties().put("nuix.registry.servers", server);
		}

		// Make sure we have a valid path to Nuix distributable
		if(nuixBaseDirectory == null){
			throw new Exception(String.format("Value provided for nuixBaseDirectory is invalid, value provided is: %s",nuixBaseDirectory));
		}

		// Warn if we don't have a certificate trust callback.  Technically this license acquisition could succeed without
		// one, but there is also a good chance it will fail.  If it fails, its good to have something in the log suggesting what
		// the user could do to resolve the issue.
		if(certificateTrustCallback == null) {
			String message = "No CertificateTrustCallback was provided.  Please either call EngineWrapper.trustAllCertificates() or "+
					"EnginerWrapper.setCertificateTrustCallback(CertificateTrustCallback certificateTrustCallback) before attempting to "+
					"obtain a license from a license server.";
			logger.warn(message);
		}

		logger.info("Creating GlobalContainer...");
		try {
			// We start by getting our hands on a GlobalContainer instance.  There can only
			// be one of these per Java process.
			if(container == null){
				container = nuix.engine.GlobalContainerFactory.newContainer();
			}

			logger.info("Initializing engine....");
			try {
				// Next we build an Engine instance from the GlobalContainer
				configureAndBuildEngine();

				logger.info("Specifying credentials to use with license server...");
				engine.whenAskedForCredentials(new CredentialsCallback() {
					Logger logger = Logger.getLogger("CredentialCallback");
					public void execute(CredentialsCallbackInfo info) {
						logger.info(String.format("Providing credentials for %s to license server %s...", userName,info.getAddress().getHostName()));
						info.setUsername(userName);
						info.setPassword(password);
					}
				});

				if(certificateTrustCallback != null) {
					engine.whenAskedForCertificateTrust(certificateTrustCallback);
				}

				logger.info("Attempting to obtain a license...");
				try {
					Map<String,Object> licenseOptions = new HashMap<String,Object>();
					licenseOptions.put("sources","server");

					boolean licenseObtained = obtainLicense(licenseOptions);
					if(licenseObtained){
						Utilities utilities = engine.getUtilities();
						ThirdPartyDependencyChecker.logAllDependencyInfo(utilities);

						// Setup our shutdown hook in case user terminates before consumer returns
						logger.info("Adding shutdown hook for EngineWrapper::close");
						Runtime.getRuntime().addShutdownHook(shutdownHook);

						logger.info("License was obtained, providing Utilities object to consumer...");
						consumer.accept(utilities);
					} else {
						logger.warn("License was not obtained");
					}
				} catch (Exception licenseException) {
					logger.error("Error obtaining license",licenseException);
					throw licenseException;
				}
			} catch (Exception engineException) {
				logger.error("Error while creating Engine instance",engineException);
				throw engineException;
			} finally {
				close();
			}
		} catch (Exception globalContainerException) {
			logger.error("Error while creating GlobalContainer",globalContainerException);
			throw globalContainerException;
		} finally {
			if(container != null){
				logger.info("Closing GlobalContainer...");
				container.close();
			}
		}
	}

	/***
	 * Attempts to obtain a server based license from NMS configured as CLS Relay.  If and when a server based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param server Location of license server (IP or hostname)
	 * @param userName Username to provide license server
	 * @param password Password to provide license server
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown.
	 */
	public void withRelayServerLicense(final String server, final String userName, final String password, Consumer<Utilities> consumer) throws Exception{
		if(server != null) {
			System.getProperties().put("nuix.registry.servers", server);
		}

		// Make sure we have a valid path to Nuix distributable
		if(nuixBaseDirectory == null){
			throw new Exception(String.format("Value provided for nuixBaseDirectory is invalid, value provided is: %s",nuixBaseDirectory));
		}

		// Warn if we don't have a certificate trust callback.  Technically this license acquisition could succeed without
		// one, but there is also a good chance it will fail.  If it fails, its good to have something in the log suggesting what
		// the user could do to resolve the issue.
		if(certificateTrustCallback == null) {
			String message = "No CertificateTrustCallback was provided.  Please either call EngineWrapper.trustAllCertificates() or "+
					"EnginerWrapper.setCertificateTrustCallback(CertificateTrustCallback certificateTrustCallback) before attempting to "+
					"obtain a license from a license server.";
			logger.warn(message);
		}

		logger.info("Creating GlobalContainer...");
		try {
			// We start by getting our hands on a GlobalContainer instance.  There can only
			// be one of these per Java process.
			if(container == null){
				container = nuix.engine.GlobalContainerFactory.newContainer();
			}

			logger.info("Initializing engine....");
			try {
				// Next we build an Engine instance from the GlobalContainer
				configureAndBuildEngine();

				logger.info("Specifying credentials to use with license server...");
				engine.whenAskedForCredentials(new CredentialsCallback() {
					Logger logger = Logger.getLogger("CredentialCallback");
					public void execute(CredentialsCallbackInfo info) {
						logger.info(String.format("Providing credentials for %s to license server %s...", userName,info.getAddress().getHostName()));
						info.setUsername(userName);
						info.setPassword(password);
					}
				});

				if(certificateTrustCallback != null) {
					engine.whenAskedForCertificateTrust(certificateTrustCallback);
				}

				logger.info("Attempting to obtain a license...");
				try {
					Map<String,Object> licenseOptions = new HashMap<String,Object>();
					licenseOptions.put("sources","local-relay-server-local-users");

					boolean licenseObtained = obtainLicense(licenseOptions);
					if(licenseObtained){
						Utilities utilities = engine.getUtilities();
						ThirdPartyDependencyChecker.logAllDependencyInfo(utilities);

						// Setup our shutdown hook in case user terminates before consumer returns
						logger.info("Adding shutdown hook for EngineWrapper::close");
						Runtime.getRuntime().addShutdownHook(shutdownHook);

						logger.info("License was obtained, providing Utilities object to consumer...");
						consumer.accept(utilities);
					} else {
						logger.warn("License was not obtained");
					}
				} catch (Exception licenseException) {
					logger.error("Error obtaining license",licenseException);
					throw licenseException;
				}
			} catch (Exception engineException) {
				logger.error("Error while creating Engine instance",engineException);
				throw engineException;
			} finally {
				close();
			}
		} catch (Exception globalContainerException) {
			logger.error("Error while creating GlobalContainer",globalContainerException);
			throw globalContainerException;
		} finally {
			if(container != null){
				logger.info("Closing GlobalContainer...");
				logger.info("Not closing GlobalContainer as it freezes the JVM, script is exiting the process anyway...");
				//container.close();

			}
		}
	}




	/***
	 * Attempts to obtain a license from NMS configured as CLS relay. The difference is that relay does not support listing of
	 * available licenses and throws an exception.
	 *
	 * If and when a server based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param server Location of license server (IP or hostname)
	 * @param userName Username to provide license server
	 * @param password Password to provide license server
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown.
	 */
	public void withNmsRelayLicense(final String server, final String userName, final String password, Consumer<Utilities> consumer) throws Exception{
		if(server != null) {
			System.getProperties().put("nuix.registry.servers", server);
		}

		// Make sure we have a valid path to Nuix distributable
		if(nuixBaseDirectory == null){
			throw new Exception(String.format("Value provided for nuixBaseDirectory is invalid, value provided is: %s",nuixBaseDirectory));
		}

		// Warn if we don't have a certificate trust callback.  Technically this license acquisition could succeed without
		// one, but there is also a good chance it will fail.  If it fails, its good to have something in the log suggesting what
		// the user could do to resolve the issue.
		if(certificateTrustCallback == null) {
			String message = "No CertificateTrustCallback was provided.  Please either call EngineWrapper.trustAllCertificates() or "+
					"EnginerWrapper.setCertificateTrustCallback(CertificateTrustCallback certificateTrustCallback) before attempting to "+
					"obtain a license from a license server.";
			logger.warn(message);
		}

		logger.info("Creating GlobalContainer...");
		try {
			// We start by getting our hands on a GlobalContainer instance.  There can only
			// be one of these per Java process.
			if(container == null){
				container = nuix.engine.GlobalContainerFactory.newContainer();
			}

			logger.info("Initializing engine....");
			try {
				// Next we build an Engine instance from the GlobalContainer
				configureAndBuildEngine();

				logger.info("Specifying credentials to use with license server...");
				engine.whenAskedForCredentials(new CredentialsCallback() {
					Logger logger = Logger.getLogger("CredentialCallback");
					public void execute(CredentialsCallbackInfo info) {
						logger.info(String.format("Providing credentials for %s to license server %s...", userName,info.getAddress().getHostName()));
						info.setUsername(userName);
						info.setPassword(password);
					}
				});

				if(certificateTrustCallback != null) {
					engine.whenAskedForCertificateTrust(certificateTrustCallback);
				}

				logger.info("Attempting to obtain a license...");
				try {
					Map<String,Object> licenseOptions = new HashMap<String,Object>();
					licenseOptions.put("sources","local-relay-server-local-users");

					boolean licenseObtained = obtainLicense(licenseOptions);
					if(licenseObtained){
						Utilities utilities = engine.getUtilities();
						ThirdPartyDependencyChecker.logAllDependencyInfo(utilities);

						// Setup our shutdown hook in case user terminates before consumer returns
						logger.info("Adding shutdown hook for EngineWrapper::close");
						Runtime.getRuntime().addShutdownHook(shutdownHook);

						logger.info("License was obtained, providing Utilities object to consumer...");
						consumer.accept(utilities);
					} else {
						logger.warn("License was not obtained");
					}
				} catch (Exception licenseException) {
					logger.error("Error obtaining license",licenseException);
					throw licenseException;
				}
			} catch (Exception engineException) {
				logger.error("Error while creating Engine instance",engineException);
				throw engineException;
			} finally {
				close();
			}
		} catch (Exception globalContainerException) {
			logger.error("Error while creating GlobalContainer",globalContainerException);
			throw globalContainerException;
		} finally {
			if(container != null){
				logger.info("Closing GlobalContainer...");
				container.close();
			}
		}
	}

	/***
	 * Attempts to obtain a cloud server based license.  If and when a cloud server based license is obtained, a Utilities object will be
	 * provided to consumer, which may then make use of the Nuix API to do work.  Once the consumer has returned this method will
	 * cleanup the Engine and GlobalContainer instances it created.
	 * @param userName Username to provide cloud license server
	 * @param password Password to provide cloud license server
	 * @param consumer Consumer which will make use of Utilities object once a license has been obtained.
	 * @throws Exception May throw exceptions due to: error creating GlobalContainer, error creating Engine instance or error obtaining license.
	 * Exceptions are caught locally, logged and then rethrown.
	 */
	public void withCloudLicense(final String userName, final String password, Consumer<Utilities> consumer) throws Exception{
		System.getProperties().put("nuix.registry.servers", "https://licence-api.nuix.com");
		
		// Make sure we have a valid path to Nuix distributable
		if(nuixBaseDirectory == null){
			throw new Exception(String.format("Value provided for nuixBaseDirectory is invalid, value provided is: %s",nuixBaseDirectory));
		}
		
		// Warn if we don't have a certificate trust callback.  Technically this license acquisition could succeed without
		// one, but there is also a good chance it will fail.  If it fails, its good to have something in the log suggesting what
		// the user could do to resolve the issue.
		if(certificateTrustCallback == null) {
			String message = "No CertificateTrustCallback was provided.  Please either call EngineWrapper.trustAllCertificates() or "+
					"EnginerWrapper.setCertificateTrustCallback(CertificateTrustCallback certificateTrustCallback) before attempting to "+
					"obtain a license from a license server.";
			logger.warn(message);
		}
		
		logger.info("Creating GlobalContainer...");
		try {
			// We start by getting our hands on a GlobalContainer instance.  There can only
			// be one of these per Java process.
			if(container == null){
				container = nuix.engine.GlobalContainerFactory.newContainer();	
			}
			
			logger.info("Initializing engine....");
			try {
				// Next we build an Engine instance from the GlobalContainer
				configureAndBuildEngine();
				
				logger.info("Specifying credentials to use with cloud license server...");
				engine.whenAskedForCredentials(new CredentialsCallback() {
					Logger logger = Logger.getLogger("CredentialCallback");
					public void execute(CredentialsCallbackInfo info) {
						logger.info(String.format("Providing credentials for %s to cloud license server %s...", userName,info.getAddress().getHostName()));
						info.setUsername(userName);
						info.setPassword(password);
					}
				});
				
				if(certificateTrustCallback != null) {
					engine.whenAskedForCertificateTrust(certificateTrustCallback);
				}
				
				logger.info("Attempting to obtain a license...");
				try {
					Map<String,Object> licenseOptions = new HashMap<String,Object>();
					licenseOptions.put("sources","cloud-server");
					
					boolean licenseObtained = obtainLicense(licenseOptions);
					if(licenseObtained){
						Utilities utilities = engine.getUtilities();
						ThirdPartyDependencyChecker.logAllDependencyInfo(utilities);

						// Setup our shutdown hook in case user terminates before consumer returns
						logger.info("Adding shutdown hook for EngineWrapper::close");
						Runtime.getRuntime().addShutdownHook(shutdownHook);
						
						logger.info("License was obtained, providing Utilities object to consumer...");
						consumer.accept(utilities);
					} else {
						logger.warn("License was not obtained");
					}
				} catch (Exception licenseException) {
					logger.error("Error obtaining license",licenseException);
					throw licenseException;
				}
			} catch (Exception engineException) {
				logger.error("Error while creating Engine instance",engineException);
				throw engineException;
			} finally {
				close();
			}
		} catch (Exception globalContainerException) {
			logger.error("Error while creating GlobalContainer",globalContainerException);
			throw globalContainerException;
		} finally {
			if(container != null){
				logger.info("Closing GlobalContainer...");
				container.close();
			}
		}
	}
	
	/***
	 * Obtains the first found license which meets the specified license options.
	 * @param licenseOptions The options passed to Licensor while finding licenses.
	 * @return True if a license was obtained, false otherwise.
	 */
	private boolean obtainLicense(Map<String,Object> licenseOptions){
		logger.info("Obtaining licensor....");
		Licensor licensor = engine.getLicensor();
		
		logger.info("Finding licences using options:");
		for(Map.Entry<String, Object> entry : licenseOptions.entrySet()){
			logger.info(String.format("\t%s: %s",entry.getKey(),entry.getValue()));
		}
		
		Iterable<AvailableLicence> licences = IterableUtils.toList(licensor.findAvailableLicences(licenseOptions));
		
		logger.info("Iterating available licences...");
		for(AvailableLicence license : licences) {
			logger.info(LicenseFeaturesLogger.summarizeLicense(license));
		}
		
		boolean licenceObtained = false;
		
		logger.info("Finding first license which meets filter requirements...");
		for(AvailableLicence license : licences) {
			logger.info("\t Count: " + license.getCount());
			logger.info("\t Workers: " + license.getWorkers());
			logger.info("\t Short Name: " + license.getShortName());
			logger.info("\t Type: " + license.getSource().getType());
			logger.info("\t ID: " + license.getSource().getLocation());
			logger.info("\t Description: " + license.getDescription());
			LicenseFeaturesLogger.logFeaturesOfLicense(license);
			
			if(licenseFilter.isValid(license)) {
				if(license.canChooseWorkers()) {
					logger.info(">>>> Acquiring this licence with "+licenseFilter.getMinWorkers()+" workers");
					int targetWorkerCount = licenseFilter.getMinWorkers();
					if(targetWorkerCount < 1) { targetWorkerCount = 2; }
					Map<String,Object> acquireSettings = new HashMap<String,Object>();
					acquireSettings.put("workerCount", targetWorkerCount);
					license.acquire(acquireSettings);
					licenceObtained = true;	
				} else {
					logger.info(">>>> Acquiring this licence");
					license.acquire();
					licenceObtained = true;	
				}
				
				break;
			} else {
				logger.info("<<<< Ignoring this license, does not meet requirements of license filter");
				continue;
			}
		}
		
		return licenceObtained;
	}
	
	/***
	 * Builds the engine instance.
	 */
	private void configureAndBuildEngine(){
		File userDataDirs = new File(nuixBaseDirectory,"user-data");
		
		logger.info("Building engine instance...");
		
		//Define our engine configuration settings
		Map<Object,Object> engineConfiguration = new HashMap<Object,Object>();
		engineConfiguration.put("user", System.getProperty("user.name"));
		engineConfiguration.put("userDataDirs", userDataDirs);
		
		logger.info("Engine initialization settings:");
		for(Map.Entry<Object, Object> entry : engineConfiguration.entrySet()){
			logger.info(String.format("\t%s: %s", entry.getKey(), entry.getValue()));
		}
		
		//Obtain an engine instance
		engine = container.newEngine(engineConfiguration);
		
		logger.info("Obtained Engine instance v"+engine.getVersion());
	}
	
	/***
	 * Provides a CertificateTrustCallback that trusts ALL certificates.  Useful for testing purposes, but it is recommended for production
	 * deployments of your solution that you provide your own CertificateTrustCallback via {@link #setCertificateTrustCallback(CertificateTrustCallback)}.
	 */
	public void trustAllCertificates() {
		certificateTrustCallback = new CertificateTrustCallback() {
			@Override
			public void execute(CertificateTrustCallbackInfo info) {
				logger.info("Trusting certificate blindly!");
				
				info.setTrusted(true);
			}
		};
	}

	public CertificateTrustCallback getCertificateTrustCallback() {
		return certificateTrustCallback;
	}

	public void setCertificateTrustCallback(CertificateTrustCallback certificateTrustCallback) {
		this.certificateTrustCallback = certificateTrustCallback;
	}

	/***
	 * Gets the root directory containing the Nuix engine distribution.
	 * @return The root directory containing the Nuix engine distribution.
	 */
	public File getNuixBaseDirectory() {
		return nuixBaseDirectory;
	}

	/***
	 * Sets the root directory containing the Nuix engine distribution.  Changing this value only has
	 * an effect before calling {@link #withDongleLicense(Consumer)} or {@link #withServerLicense(String, String, Consumer)}.
	 * @param nuixBaseDirectory The directory containing the Nuix engine distribution.
	 */
	public void setNuixBaseDirectory(File nuixBaseDirectory) {
		this.nuixBaseDirectory = nuixBaseDirectory;
	}
	
	/***
	 * Sets the root directory containing the Nuix engine distribution.  Changing this value only has
	 * an effect before calling {@link #withDongleLicense(Consumer)} or {@link #withServerLicense(String, String, Consumer)}.
	 * @param nuixBaseDirectory The directory containing the Nuix engine distribution.
	 */
	public void setNuixBaseDirectory(String nuixBaseDirectory) {
		this.nuixBaseDirectory = new File(nuixBaseDirectory);
	}

	/***
	 * Gets the {@link LicenseFilter} instance used to determine which available license to acquire.
	 * @return The {@link LicenseFilter} instance used to determine which available license to acquire.
	 */
	public LicenseFilter getLicenseFilter() {
		return licenseFilter;
	}

	/***
	 * Sets the {@link LicenseFilter} instance used to determine which available license to acquire.
	 * @param licenseFilter The {@link LicenseFilter} instance to use to determine which available license to acquire.
	 */
	public void setLicenseFilter(LicenseFilter licenseFilter) {
		this.licenseFilter = licenseFilter;
	}

	@Override
	public void close() throws Exception {
		if(engine != null) {
			logger.info("Closing engine instance");
			engine.close();
		}
		
		// Remove our shutdown hook since engine has now been closed
		logger.info("Removing shutdown hook to EngineWrapper::close");
		Runtime.getRuntime().removeShutdownHook(shutdownHook);
	}
}
