/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.service;

import com.googlecode.junit.ext.JunitExtSpringRunner;
import com.googlecode.junit.ext.RunIf;
import com.googlecode.junit.ext.checkers.SocketChecker;
import com.thoughtworks.go.config.GoConfigFileDao;
import com.thoughtworks.go.config.MailHost;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.domain.DefaultSchedulingContext;
import com.thoughtworks.go.domain.JobInstance;
import com.thoughtworks.go.domain.JobResult;
import com.thoughtworks.go.domain.JobState;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.domain.User;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.remote.AgentIdentifier;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.UserDao;
import com.thoughtworks.go.server.messaging.BuildRepositoryMessageProducer;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.TimeProvider;
import com.thoughtworks.go.utils.Pop3MailClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

import static com.thoughtworks.go.helper.BuildPlanMother.withBuildPlans;
import static com.thoughtworks.go.helper.ModificationsMother.MOD_FILE_BUILD_XML;
import static com.thoughtworks.go.helper.ModificationsMother.modifyOneFile;
import static com.thoughtworks.go.helper.Pop3Matchers.emailContentContains;
import static com.thoughtworks.go.helper.Pop3Matchers.emailSubjectContains;
import static com.thoughtworks.go.utils.Assertions.assertWillHappen;
import static org.hamcrest.core.IsNot.not;

@Ignore("fails randomly")
@RunWith(JunitExtSpringRunner.class)
@ContextConfiguration(locations = {
        "classpath:WEB-INF/applicationContext-global.xml",
        "classpath:WEB-INF/applicationContext-dataLocalAccess.xml",
        "classpath:WEB-INF/spring-cruise-remoting-servlet.xml"
        }
)
public class EmailNotificationIntegerationTest {
    @Autowired private PipelineService pipelineService;
    @Autowired private GoConfigFileDao goConfigFileDao;
    @Autowired private UserDao usersettingDao;
    @Autowired private BuildRepositoryMessageProducer producer;
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private InstanceFactory instanceFactory;

    private static final String PIPELINE_NAME = "mingle";
    private static final String STAGE_NAME = "dev";
    private static final String UNIT_LINUX = "unit-linux";
    private static final String UNIT_WINDOWS = "unit-windows";
    private Pop3MailClient pop3MailClient;

    private JobInstance job;
    private PipelineConfig pipelineConfig;

    private Pipeline pipeline;
    private Stage stage;
    private Pipeline savePipeline;
    private static GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private static final String HOST_NAME = "10.18.3.171";
    private static final int PORT = 25;
    private static final String USERNAME = "cruise2";
    private static final String PASSWORD = "password123";
    private static final String FROM = "cruise2@cruise.com";
    private static final String TO = "cruise2@cruise.com";
    private String userName;

    @Before
    public void setUp() throws Exception {
        userName = "GAO LI";
        configHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigFileDao).initializeConfigFile();

        dbHelper.onSetUp();
        pipelineConfig = PipelineMother.withSingleStageWithMaterials(PIPELINE_NAME, STAGE_NAME, withBuildPlans(
                UNIT_LINUX, UNIT_WINDOWS));
        pipelineConfig.setMaterialConfigs(MaterialConfigsMother.multipleMaterialConfigs());

        pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createWithModifications(modifyOneFile(pipelineConfig), ""),
                new DefaultSchedulingContext(
                        GoConstants.DEFAULT_APPROVED_BY), "md5-test", new TimeProvider());

        savePipeline = pipelineService.save(pipeline);

        configHelper.addMailHost(new MailHost(HOST_NAME, PORT, USERNAME, PASSWORD, true, false, FROM, TO));
        usersettingDao.saveOrUpdate(new User(userName, new String[]{"lgao.*"}, "cruise2@cruise.com", true));
        pop3MailClient = new Pop3MailClient(HOST_NAME, 110, USERNAME, PASSWORD);
        pop3MailClient.deleteAllMessages();
    }

    @After
    public void teardown() throws Exception {
        dbHelper.onTearDown();
        pop3MailClient.deleteAllMessages();
    }

    @Test
    @RunIf(value = SocketChecker.class, arguments = {"10.18.3.171", "25" })
    public void shouldSendOutNotificationMailIfStageIsCompleted() throws Exception {
        configHelper.addSecurityWithAdminConfig();

        agentReportJobIsCompleted(UNIT_LINUX);
        agentReportJobIsCompleted(UNIT_WINDOWS);
        final String expectedSubject = job.getIdentifier().getStageIdentifier().stageLocator();

        assertWillHappen(pop3MailClient, emailSubjectContains(expectedSubject));
        assertWillHappen(pop3MailClient, emailContentContains("Sent by Cruise on behalf of " + userName));
        assertWillHappen(pop3MailClient, emailContentContains(MOD_FILE_BUILD_XML));
    }

    @Test
    @RunIf(value = SocketChecker.class, arguments = {"10.18.3.171", "25" })
    public void shouldNotSendOutNotificationMailIfSecurityIsDisabled() throws Exception {
        agentReportJobIsCompleted(UNIT_LINUX);
        agentReportJobIsCompleted(UNIT_WINDOWS);

        final String expectedSubject = job.getIdentifier().getStageIdentifier().stageLocator();
        assertWillHappen(pop3MailClient, not(emailSubjectContains(expectedSubject)));
    }

    private void agentReportJobIsCompleted(String unitLinux) {
        job = pipeline.getFirstStage().findJob(unitLinux);

        producer.reportCompleting(AgentRuntimeInfo.fromAgent(new AgentIdentifier("localhost", "127.0.0.1", job.getAgentUuid()), "cookie", null), job.getIdentifier(),
                JobResult.Failed);
        producer.reportCurrentStatus(AgentRuntimeInfo.fromAgent(new AgentIdentifier("", "", job.getAgentUuid()), "cookie", null), job.getIdentifier(),
                JobState.Completed);
    }
}
