/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.marklogic.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.nifi.components.state.Scope;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.marklogic.client.datamovement.QueryBatcher;
import com.marklogic.client.datamovement.WriteBatcher;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.StringHandle;

public class QueryMarkLogicIT extends AbstractMarkLogicIT {
    private String collection;

    @BeforeEach
    public void setup() {
        super.setup();
        collection = "QueryMarkLogicTest";
        // Load documents to Query
        loadDocumentsIntoCollection(collection, documents);
    }

    private void loadDocumentsIntoCollection(String collection, List<IngestDoc> documents) {
        WriteBatcher writeBatcher = dataMovementManager.newWriteBatcher()
            .withBatchSize(3)
            .withThreadCount(3);
        dataMovementManager.startJob(writeBatcher);
        for(IngestDoc document : documents) {
            DocumentMetadataHandle handle = new DocumentMetadataHandle();
            handle.withCollections(collection);
            writeBatcher.add(document.getFileName(), handle, new StringHandle(document.getContent()));
        }
        writeBatcher.flushAndWait();
        dataMovementManager.stopJob(writeBatcher);
    }

    protected TestRunner getNewTestRunner(Class processor) {
        TestRunner runner = super.getNewTestRunner(processor);
        runner.assertNotValid();
        runner.setProperty(QueryMarkLogic.CONSISTENT_SNAPSHOT, "true");
        return runner;
    }

    @Test
    public void testSimpleCollectionQuery() {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, collection);
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COLLECTION);
        runner.enqueue(new MockFlowFile(12345));
        runner.assertValid();
        runner.run();

        runner.assertTransferCount(QueryMarkLogic.ORIGINAL, 1);
        MockFlowFile originalFlowFile = runner.getFlowFilesForRelationship(QueryMarkLogic.ORIGINAL).get(0);
        assertEquals("If a FlowFile is passed to DeleteML/QueryML, it is expected to be sent to the " +
                "ORIGINAL relationship before the job completes", 12345, originalFlowFile.getId());

        runner.assertTransferCount(QueryMarkLogic.SUCCESS, numDocs);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/" + Integer.toString(jsonMod) + ".json")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(jsonMod).getContent().getBytes();
        assertEquals(expectedByteArray.length, actualByteArray.length);
        assertTrue(Arrays.equals(expectedByteArray, actualByteArray));
    }

    @Test
    public void testOldCollectionQuery() {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.COLLECTIONS, collection);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, numDocs);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/" + Integer.toString(jsonMod) + ".json")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(jsonMod).getContent().getBytes();
        assertEquals(expectedByteArray.length, actualByteArray.length);
        assertTrue(Arrays.equals(expectedByteArray, actualByteArray));
    }

    @Test
    public void testCombinedJSONQuery() throws InitializationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "{\"search\" : {\n" +
                "  \"ctsquery\": {\n" +
                "    \"jsonPropertyValueQuery\":{\n" +
                "      \"property\":[\"sample\"],\n" +
                "      \"value\":[\"jsoncontent\"]\n" +
                "      } \n" +
                "  }\n" +
                "} }");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COMBINED_JSON);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedJsonCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedJsonCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/" + Integer.toString(jsonMod) + ".json")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(jsonMod).getContent().getBytes();
        assertEquals(expectedByteArray.length, actualByteArray.length);
        assertTrue(Arrays.equals(expectedByteArray, actualByteArray));
        runner.shutdown();
    }

    @Test
    public void testStateManagerWithCombinedQuery() throws InitializationException, IOException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        // test with string query
        runner.setProperty(QueryMarkLogic.QUERY, "{\"search\" : {\n" +
                "  \"ctsquery\": {\n" +
                "    \"jsonPropertyValueQuery\":{\n" +
                "      \"property\":[\"sample\"],\n" +
                "      \"value\":[\"jsoncontent\"]\n" +
                "      } \n" +
                "  }\n" +
                "} }");
        runner.setProperty(QueryMarkLogic.RETURN_TYPE, QueryMarkLogic.ReturnTypes.DOCUMENTS_AND_META);
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COMBINED_JSON);
        testStateManagerJSON(runner, expectedJsonCount);
        runner.setProperty(QueryMarkLogic.QUERY, "<cts:element-value-query xmlns:cts=\"http://marklogic.com/cts\">\n" +
                "  <cts:element>sample</cts:element>\n" +
                "  <cts:text xml:lang=\"en\">xmlcontent</cts:text>\n" +
                "</cts:element-value-query>");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COMBINED_XML);
        testStateManagerXML(runner, expectedXmlCount);
        runner.shutdown();
    }

    @Test
    public void testStateManagerWithStructuredQuery() throws InitializationException, IOException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        // test with string query
        runner.setProperty(QueryMarkLogic.QUERY, "{\n" +
                "  \"query\": {\n" +
                "    \"queries\": [\n" +
                "      { \n" +
                "       \"value-query\": {\n" +
                "          \"type\": \"string\",\n" +
                "          \"json-property\": [\"sample\"],\n" +
                "          \"text\": [\"jsoncontent\"]\n" +
                "        }" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRUCTURED_JSON);
        testStateManagerJSON(runner, expectedJsonCount);
        runner.setProperty(QueryMarkLogic.QUERY, "<query xmlns=\"http://marklogic.com/appservices/search\">\n" +
                "  <word-query>\n" +
                "    <element name=\"sample\" ns=\"\" />\n" +
                "    <text>xmlcontent</text>\n" +
                "  </word-query>\n" +
                "</query>");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRUCTURED_XML);
        testStateManagerXML(runner, expectedXmlCount);
        runner.shutdown();
    }

    @Test
    public void testStateManagerWithJSONStringQuery() throws InitializationException, IOException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "jsoncontent");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRING);
        testStateManagerJSON(runner, expectedJsonCount);
        runner.shutdown();
    }

    /**
     * This test was failing when the incubator 1.9.1.4 code was brought over. The failure is due to the "nst"
     * namespace not being recognized when used in a path range index query. Not certain yet if the test is buggy or
     * if the app code is buggy.
     *
     * @throws InitializationException
     * @throws IOException
     */
    @Test
    public void testStateManagerWithXMLStringQuery() throws InitializationException, IOException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "xmlcontent");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRING);
        testStateManagerXML(runner, expectedXmlCount);
        runner.shutdown();
    }

    private void testStateManagerJSON(TestRunner runner, int expectedCount) throws InitializationException, IOException {
        runner.setProperty(QueryMarkLogic.STATE_INDEX, "dateTime");
        runner.setProperty(QueryMarkLogic.STATE_INDEX_TYPE, QueryMarkLogic.IndexTypes.JSON_PROPERTY);
        testStateManagerWithIndex(runner, expectedCount);
    }

    private void testStateManagerXML(TestRunner runner, int expectedCount) throws InitializationException, IOException {
        runner.setProperty("ns:nst", "namespace-test");
        runner.setProperty(QueryMarkLogic.STATE_INDEX, "/root/nst:dateTime");
        runner.setProperty(QueryMarkLogic.STATE_INDEX_TYPE, QueryMarkLogic.IndexTypes.PATH);
        testStateManagerWithIndex(runner, expectedCount);
        runner.setProperty("ns:t", "namespace-test");
        runner.setProperty(QueryMarkLogic.STATE_INDEX, "t:dateTime");
        runner.setProperty(QueryMarkLogic.STATE_INDEX_TYPE, QueryMarkLogic.IndexTypes.ELEMENT);
        testStateManagerWithIndex(runner, expectedCount);
    }

    private void testStateManagerWithIndex(TestRunner runner, int expectedCount) throws InitializationException, IOException {
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedCount);
        runner.clearTransferState();
        runner.getStateManager().assertStateEquals("queryState", "2000-01-01T00:00:00", Scope.CLUSTER);
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, 0);
        runner.clearTransferState();
        HashMap<String,String> state = new HashMap<String,String>();
        state.put("queryState", "1999-01-01T00:00:00");
        runner.getStateManager().setState(state, Scope.CLUSTER);
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedCount);
        runner.clearTransferState();
        runner.getStateManager().setState(new HashMap<String,String>(), Scope.CLUSTER);
    }

    @Test
    public void testCombinedXMLQuery() throws InitializationException, SAXException, IOException, ParserConfigurationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "<cts:element-value-query xmlns:cts=\"http://marklogic.com/cts\">\n" +
                "  <cts:element>sample</cts:element>\n" +
                "  <cts:text xml:lang=\"en\">xmlcontent</cts:text>\n" +
                "</cts:element-value-query>");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COMBINED_XML);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedXmlCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedXmlCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/"+ Integer.toString(xmlMod) +".xml")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(xmlMod).getContent().getBytes();

        assertBytesAreEqualXMLDocs(expectedByteArray,actualByteArray);
        runner.shutdown();
    }

    @Test
    public void testStructuredJSONQuery() throws InitializationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "{\n" +
                "  \"query\": {\n" +
                "    \"queries\": [\n" +
                "      { \n" +
                "       \"value-query\": {\n" +
                "          \"type\": \"string\",\n" +
                "          \"json-property\": [\"sample\"],\n" +
                "          \"text\": [\"jsoncontent\"]\n" +
                "        }" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRUCTURED_JSON);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedJsonCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedJsonCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/" + Integer.toString(jsonMod) + ".json")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(jsonMod).getContent().getBytes();
        assertEquals(expectedByteArray.length, actualByteArray.length);
        assertTrue(Arrays.equals(expectedByteArray, actualByteArray));
        runner.shutdown();
    }

    @Test
    public void testStructuredXMLQuery() throws SAXException, IOException, ParserConfigurationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        Map<String,String> attributes = new HashMap<>();
        attributes.put("word", "xmlcontent");
        runner.enqueue("".getBytes(), attributes);
        runner.setProperty(QueryMarkLogic.QUERY, "<query xmlns=\"http://marklogic.com/appservices/search\">\n" +
                "  <word-query>\n" +
                "    <element name=\"sample\" ns=\"\" />\n" +
                "    <text>${word}</text>\n" +
                "  </word-query>\n" +
                "</query>");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRUCTURED_XML);
        runner.assertValid();
        runner.run();

        // The single enqueued doc should show up here
        runner.assertTransferCount(QueryMarkLogic.ORIGINAL, 1);

        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedXmlCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedXmlCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/"+ Integer.toString(xmlMod) +".xml")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(xmlMod).getContent().getBytes();

        assertBytesAreEqualXMLDocs(expectedByteArray,actualByteArray);
        runner.shutdown();
    }

    @Test
    public void testStringQuery() throws InitializationException, SAXException, IOException, ParserConfigurationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, "xmlcontent");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRING);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedXmlCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedXmlCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            if(flowFile.getAttribute(CoreAttributes.FILENAME.key()).endsWith("/"+ Integer.toString(xmlMod) +".xml")) {
                actualByteArray = runner.getContentAsByteArray(flowFile);
                break;
            }
        }
        byte[] expectedByteArray = documents.get(xmlMod).getContent().getBytes();

        assertBytesAreEqualXMLDocs(expectedByteArray,actualByteArray);
        runner.shutdown();
    }

    @Test
    public void testUrisOnly() throws InitializationException, SAXException, IOException, ParserConfigurationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.RETURN_TYPE, QueryMarkLogic.ReturnTypes.META);
        runner.setProperty(QueryMarkLogic.QUERY, "xmlcontent");
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.STRING);
        runner.assertValid();
        runner.run();
        runner.assertTransferCount(QueryMarkLogic.SUCCESS, expectedXmlCount);
        runner.assertAllFlowFilesContainAttribute(QueryMarkLogic.SUCCESS,CoreAttributes.FILENAME.key());
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(QueryMarkLogic.SUCCESS);
        assertEquals(flowFiles.size(), expectedXmlCount);
        byte[] actualByteArray = null;
        for(MockFlowFile flowFile : flowFiles) {
            actualByteArray = runner.getContentAsByteArray(flowFile);
            assertEquals(actualByteArray.length,0);
        }
        runner.shutdown();
    }

    @Test
    public void testJobProperties() throws InitializationException {
        TestRunner runner = getNewTestRunner(QueryMarkLogic.class);
        runner.setProperty(QueryMarkLogic.QUERY, collection);
        runner.setProperty(QueryMarkLogic.QUERY_TYPE, QueryMarkLogic.QueryTypes.COLLECTION);
        runner.run();
        Processor processor = runner.getProcessor();
        if(processor instanceof QueryMarkLogic) {
            QueryBatcher queryBatcher = ((QueryMarkLogic) processor).getQueryBatcher();
            assertEquals(Integer.parseInt(batchSize), queryBatcher.getBatchSize());
            assertEquals(Integer.parseInt(threadCount), queryBatcher.getThreadCount());
        } else {
            fail("Processor not an instance of QueryMarkLogic");
        }
        runner.shutdown();
    }

    private void assertBytesAreEqualXMLDocs(byte[] expectedByteArray, byte[] actualByteArray) throws SAXException, IOException, ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setCoalescing(true);
        dbf.setIgnoringElementContentWhitespace(true);
        dbf.setIgnoringComments(true);
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc1 = db.parse(new ByteArrayInputStream(actualByteArray));
        doc1.normalizeDocument();

        Document doc2 = db.parse(new ByteArrayInputStream(expectedByteArray));
        doc2.normalizeDocument();

        assertTrue(doc1.isEqualNode(doc2));
    }
}
