package uk.ac.kcl.testexecutionlisteners;


import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import uk.ac.kcl.it.PostGresTestUtils;
import uk.ac.kcl.it.TestUtils;

/**
 * Created by rich on 03/06/16.
 */
public class PostgresFullPipelineTestExecutionListener extends AbstractTestExecutionListener {

    public PostgresFullPipelineTestExecutionListener(){}

    @Override
    public void beforeTestClass(TestContext testContext) {
        PostGresTestUtils postgresTestUtils =
                testContext.getApplicationContext().getBean(PostGresTestUtils.class);
        postgresTestUtils.createJobRepository();
        postgresTestUtils.createDeIdInputTable();
        postgresTestUtils.createTikaTable();
        postgresTestUtils.createBasicOutputTable();
        TestUtils testUtils =
                testContext.getApplicationContext().getBean(TestUtils.class);
        testUtils.insertTestDataForFullPipeline("tblIdentifiers","tblInputDocs",1);
    }

}
