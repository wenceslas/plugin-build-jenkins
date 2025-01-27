/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.transaction.Transactional;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.iam.model.DelegateOrg;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.build.BuildResource;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xml.sax.SAXException;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.UrlPattern;

/**
 * Test class of {@link JenkinsPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class JenkinsPluginResourceTest extends AbstractServerTest {
	@Autowired
	private JenkinsPluginResource resource;

	@Autowired
	private ParameterValueResource pvResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class,
				ParameterValue.class, DelegateOrg.class }, StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Coverage only
		resource.getKey();
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, BuildResource.SERVICE_KEY);
	}

	@Test
	void deleteLocal() throws MalformedURLException, URISyntaxException  {
		resource.delete(subscription, false);
		// nothing has been done. If remote delete is done, an exception will be
		// thrown and this test will fail.
	}

	@Test
	void deleteRemote() throws IOException, URISyntaxException  {
		addLoginAccess();
		addAdminAccess();

		// post delete
		final UrlPattern deletePath = urlEqualTo("/job/ligoj-bootstrap/doDelete");
		httpServer.stubFor(post(deletePath).willReturn(
				aResponse().withHeader("location", "location").withStatus(HttpStatus.SC_MOVED_TEMPORARILY)));
		httpServer.start();

		resource.delete(subscription, true);

		// check that server has been called.
		httpServer.verify(1, WireMock.postRequestedFor(deletePath));
	}

	@Test
	void deleteRemoteFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();

		// post delete
		final UrlPattern deletePath = urlEqualTo("/job/ligoj-bootstrap/doDelete");
		httpServer.stubFor(post(deletePath).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();

		Assertions.assertThrows(BusinessException.class, () -> {
			resource.delete(subscription, true);
		});
	}

	@Test
	void getJenkinsResourceInvalidUrl() {
		resource.getResource(new HashMap<>(), null);
	}

	@Test
	void getVersion() throws Exception {
		addAdminAccess();
		httpServer.start();
		final String version = resource.getVersion(subscription);
		Assertions.assertEquals("1.574", version);
	}

	@Test
	void getLastVersion() {
		final String lastVersion = resource.getLastVersion();
		Assertions.assertNotNull(lastVersion);
		Assertions.assertTrue(lastVersion.compareTo("1.576") > 0);
	}

	@Test
	void getLastVersionFailed() {
		Assertions.assertNull(resource.getLastVersion("any:some"));
	}

	@Test
	void validateJobNotFound() {
		httpServer.stubFor(get(urlEqualTo("/job/ligoj-bootstrap/config.xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();

		final Map<String, String> parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.validateJob(parameters);
		}), JenkinsPluginResource.PARAMETER_JOB, "jenkins-job");
	}

	@Test
	void link() throws IOException, URISyntaxException {
		addLoginAccess();
		addAdminAccess();
		addJobAccess();
		httpServer.start();

		// Attach the Jenkins project identifier
		final Parameter parameter = new Parameter();
		parameter.setId(JenkinsPluginResource.PARAMETER_JOB);
		final Subscription subscription = new Subscription();
		final Subscription source = em.find(Subscription.class, this.subscription);
		subscription.setProject(source.getProject());
		subscription.setNode(source.getNode());
		em.persist(subscription);
		final ParameterValue parameterValue = new ParameterValue();
		parameterValue.setParameter(parameter);
		parameterValue.setData("ligoj-bootstrap");
		parameterValue.setSubscription(subscription);
		em.persist(parameterValue);
		em.flush();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour jenkins
		resource.link(subscription.getId());

		// Nothing to validate for now...
	}

	@Test
	void validateJob() throws IOException, URISyntaxException {
		addJobAccess();
		httpServer.start();

		final Map<String, String> parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		checkJob(resource.validateJob(parameters), false);
	}

	@Test
	void validateJobSimple() throws IOException, URISyntaxException {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?depth=1&tree=jobs[displayName,name,color]&xpath=hudson/job[name='ligoj-bootstrap']&wrapper=hudson"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(new ClassPathResource(
										"mock-server/jenkins/jenkins-ligoj-bootstrap-config-simple.xml").getInputStream(),
										StandardCharsets.UTF_8))));
		httpServer.start();

		final Map<String, String> parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		final Job job = resource.validateJob(parameters);
		Assertions.assertEquals("ligoj-bootstrap", job.getId());
		Assertions.assertNull(job.getName());
		Assertions.assertNull(job.getDescription());
		Assertions.assertEquals("disabled", job.getStatus());
		Assertions.assertFalse(job.isBuilding());
	}

	@Test
	void validateJobBuilding() throws IOException, URISyntaxException {
		addJobAccessBuilding();
		httpServer.start();

		final Map<String, String> parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		checkJob(resource.validateJob(parameters), true);
	}

	private void checkJob(final Job job, final boolean building) {
		Assertions.assertEquals("ligoj-bootstrap", job.getId());
		Assertions.assertEquals("Ligoj - Bootstrap", job.getName());
		Assertions.assertEquals("Any description", job.getDescription());
		Assertions.assertEquals("yellow", job.getStatus());
		Assertions.assertEquals(building, job.isBuilding());
	}

	@Test
	void checkStatus() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();

		final Map<String, String> parametersNoCheck = subscriptionResource.getParametersNoCheck(subscription);
		parametersNoCheck.remove(JenkinsPluginResource.PARAMETER_JOB);
		Assertions.assertTrue(resource.checkStatus(parametersNoCheck));
	}

	@Test
	void checkSubscriptionStatus() throws IOException, URISyntaxException {
		addJobAccess();
		httpServer.start();

		final SubscriptionStatusWithData nodeStatusWithData = resource
				.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkJob((Job) nodeStatusWithData.getData().get("job"), false);
	}

	private void addJobAccess() throws IOException {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?depth=1&tree=jobs[displayName,name,color]&xpath=hudson/job[name='ligoj-bootstrap']&wrapper=hudson"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/jenkins/jenkins-ligoj-bootstrap-config.xml")
												.getInputStream(),
										StandardCharsets.UTF_8))));
	}

	private void addJobAccessBuilding() throws IOException {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?depth=1&tree=jobs[displayName,name,color]&xpath=hudson/job[name='ligoj-bootstrap']&wrapper=hudson"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-ligoj-bootstrap-config-building.xml")
										.getInputStream(),
								StandardCharsets.UTF_8))));
	}

	@Test
	void validateAdminAccess() throws IOException {
		addLoginAccess();
		addAdminAccess();
		addJobAccess();
		httpServer.start();

		final String version = resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"));
		Assertions.assertEquals("1.574", version);
	}

	private void addAdminAccess() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/api/json?tree=numExecutors"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withHeader("x-jenkins", "1.574")
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-version.json").getInputStream(),
								StandardCharsets.UTF_8))));
	}

	@Test
	void validateAdminAccessConnectivityFail() {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"));
		}), JenkinsPluginResource.PARAMETER_URL, "jenkins-connection");
	}

	@Test
	void validateAdminAccessLoginFail() {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"));
		}), JenkinsPluginResource.PARAMETER_USER, "jenkins-login");
	}

	@Test
	void validateAdminAccessNoRight() throws IOException {
		addLoginAccess();
		httpServer.stubFor(get(urlEqualTo("/computer/(master)/config.xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"));
		}), JenkinsPluginResource.PARAMETER_USER, "jenkins-rights");
	}

	@Test
	void findAllByName() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(get(urlPathEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-api-xml-tree.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		httpServer.start();
		final List<Job> jobs = resource.findAllByName("service:build:jenkins:bpr", "ligoj");
		checkAll(jobs);
	}

	private void checkAll(final List<Job> jobs) {
		Assertions.assertEquals(29, jobs.size());
		Assertions.assertEquals("Ligoj - Cron - SSE", jobs.get(6).getName());
		Assertions.assertEquals("CRON - Projet SSE", jobs.get(6).getDescription());
		Assertions.assertEquals("ligoj-cron-sse", jobs.get(6).getId());
		Assertions.assertEquals("disabled", jobs.get(6).getStatus());
	}

	@Test
	void findAllTemplateByName() throws IOException, SAXException, ParserConfigurationException  {
		httpServer.stubFor(
				get(urlPathEqualTo("/view/Templates/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-api-xml-tree.xml").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.start();
		final List<Job> jobs = resource.findAllTemplateByName("service:build:jenkins:bpr", "ligoj");
		checkAll(jobs);
	}

	/**
	 * Bad credential
	 */
	@Test
	void findAllByNameFailed() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(get(urlPathEqualTo("/api/xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED).withBody("<html>FORBIDDEN</html>")));
		httpServer.start();
		Assertions.assertEquals(0, resource.findAllByName("service:build:jenkins:bpr", "ligoj").size());
	}

	@Test
	void findById() throws IOException, URISyntaxException {
		addJobAccessBuilding();
		httpServer.start();
		checkJob(resource.findById("service:build:jenkins:bpr", "ligoj-bootstrap"), true);
	}

	@Test
	void findByIdFail() {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?depth=1&tree=jobs[displayName,name,color]&xpath=hudson/job[name='ligoj-bootstraps']&wrapper=hudson"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<hudson/>")));
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.findById("service:build:jenkins:bpr", "ligoj-bootstraps");
		}), "service:build:jenkins:job", "jenkins-job");
	}

	private void addLoginAccess() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-api-xml.xml").getInputStream(),
						StandardCharsets.UTF_8))));
	}

	@Test
	void create() throws IOException, URISyntaxException {
		addLoginAccess();
		addAdminAccess();

		// retrieve template config.xml
		httpServer.stubFor(get(urlEqualTo("/job/template/config.xml")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-template-config.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		// post new job config.xml
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap"))
				.withRequestBody(WireMock.containing("fdaugan@sample.com"))
				.withRequestBody(WireMock.containing("<disabled>false</disabled>"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();

		// prepare new subscription
		final Subscription subscription = em.find(Subscription.class, this.subscription);
		createParameterValueTemplateJob(subscription);
		this.resource.create(this.subscription);
	}

	@Test
	void createFailed() throws IOException  {
		addLoginAccess();
		addAdminAccess();

		// retrieve template config.xml
		httpServer.stubFor(get(urlEqualTo("/job/template/config.xml")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-template-config.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		// post new job config.xml
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)));
		httpServer.start();

		// prepare new subscription
		final Subscription subscription = em.find(Subscription.class, this.subscription);
		createParameterValueTemplateJob(subscription);
		Assertions.assertThrows(BusinessException.class, () -> {
			this.resource.create(this.subscription);
		});
	}

	/**
	 * create a parameter value for template Job definition
	 *
	 * @param subscription
	 *            future parameter value linked subscription
	 */
	private void createParameterValueTemplateJob(final Subscription subscription) {
		final ParameterValue parameterValue = new ParameterValue();
		parameterValue.setParameter(em.find(Parameter.class, "service:build:jenkins:template-job"));
		parameterValue.setSubscription(subscription);
		parameterValue.setData("template");
		em.persist(parameterValue);
		em.flush();
	}

	@Test
	void buildFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		Assertions.assertThrows(BusinessException.class, () -> {
			this.resource.build(subscription);
		});
	}

	@Test
	void build() throws IOException  {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(
				post(urlEqualTo("/job/ligoj-bootstrap/build")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		this.resource.build(subscription);
	}

	@Test
	void buildInvalidUrl() {
		@SuppressWarnings("unchecked")
		final Map<String, String> map = Mockito.mock(Map.class);
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_USER)).thenReturn("some");
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_TOKEN)).thenReturn("some");
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_URL)).thenThrow(new RuntimeException());
		Assertions.assertThrows(RuntimeException.class, () -> {
			this.resource.build(map, null);
		});
	}

	@Test
	void buildParameters() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/build"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)));
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/buildWithParameters"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		this.resource.build(subscription);

	}

}
