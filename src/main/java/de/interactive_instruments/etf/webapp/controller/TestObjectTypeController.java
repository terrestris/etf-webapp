/**
 * Copyright 2010-2017 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.webapp.controller;

import static de.interactive_instruments.etf.webapp.SwaggerConfig.SERVICE_CAP_TAG_NAME;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import de.interactive_instruments.etf.detector.DetectedTestObjectType;
import de.interactive_instruments.etf.detector.TestObjectTypeNotDetected;
import de.interactive_instruments.etf.model.capabilities.Resource;
import de.interactive_instruments.etf.model.capabilities.SecuredResource;
import de.interactive_instruments.etf.model.capabilities.StdResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;

import de.interactive_instruments.Credentials;
import de.interactive_instruments.IFile;
import de.interactive_instruments.SUtils;
import de.interactive_instruments.UriUtils;
import de.interactive_instruments.etf.dal.dao.Dao;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.detector.TestObjectTypeDetectorManager;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidMap;
import de.interactive_instruments.etf.webapp.WebAppConstants;
import de.interactive_instruments.etf.webapp.conversion.EidConverter;
import de.interactive_instruments.etf.webapp.helpers.SimpleFilter;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.ConfigurationException;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@RestController
public class TestObjectTypeController {

	@Autowired
	private DataStorageService dataStorageService;

	@Autowired
	private StreamingService streaming;

	private final Logger logger = LoggerFactory.getLogger(TestObjectTypeController.class);

	private Dao<TestObjectTypeDto> testObjectTypeDao;
	private final static String TEST_OBJECT_TYPES_URL = WebAppConstants.API_BASE_URL + "/TestObjectTypes";

	private final static String TEST_OBJECT_TYPE_DESCRIPTION = "The Test Object model is described in the "
			+ "[XML schema documentation](https://services.interactive-instruments.de/etf/schemadoc/capabilities_xsd.html#TestObjectType) "
			+ ETF_ITEM_COLLECTION_DESCRIPTION;

	@PostConstruct
	private void init() throws IOException, TransformerConfigurationException, ObjectWithIdNotFoundException {
		testObjectTypeDao = dataStorageService.getDao(TestObjectTypeDto.class);
		final EidMap<TestObjectTypeDto> supportedTypes = TestObjectTypeDetectorManager.getSupportedTypes();
		((WriteDao) testObjectTypeDao).deleteAllExisting(supportedTypes.keySet());
		((WriteDao) testObjectTypeDao).addAll(supportedTypes.values());

		streaming.prepareCache(testObjectTypeDao, new SimpleFilter());
		logger.info("Test Object Type controller initialized");
	}

	public void checkAndResolveTypes(final TestObjectDto dto) throws IOException, LocalizableApiError {
		// First resource is the main resource
		final ResourceDto resourceDto = dto.getResourceCollection().iterator().next();
		final Resource resource;
		if (UriUtils.isFile(resourceDto.getUri())) {
			resource = new StdResource(resourceDto.getName(), resourceDto.getUri());
		} else {
			dto.setRemoteResource(resourceDto.getUri());
			resource = new SecuredResource(resourceDto.getName(),
					Credentials.fromProperties(dto.properties()), resourceDto.getUri());
		}
		final DetectedTestObjectType detectedTestObjectType;
		try {
			detectedTestObjectType = TestObjectTypeDetectorManager.detect(resource);
		} catch (final TestObjectTypeNotDetected e) {
			throw new LocalizableApiError(e);
		}
		detectedTestObjectType.enrichAndNormalize(dto);
	}

	//
	// Rest interfaces
	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	@ApiOperation(value = "Get Test Object Type as JSON", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
			SERVICE_CAP_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "Test Object Type"),
			@ApiResponse(code = 404, message = "Test Object Type not found")
	})
	@RequestMapping(value = {TEST_OBJECT_TYPES_URL + "/{id}", TEST_OBJECT_TYPES_URL + "/{id}.json"}, method = RequestMethod.GET)
	public void testObjectTypesByIdJson(
			@ApiParam(value = EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
			HttpServletRequest request,
			HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
		streaming.asJson2(testObjectTypeDao, request, response, id);
	}

	@ApiOperation(value = "Get multiple Test Object Types as JSON", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
			SERVICE_CAP_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Object Types")
	})
	@RequestMapping(value = {TEST_OBJECT_TYPES_URL, TEST_OBJECT_TYPES_URL + ".json"}, method = RequestMethod.GET)
	public void listTestObjectTypesJson(
			@ApiParam(value = OFFSET_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int offset,
			@ApiParam(value = LIMIT_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int limit,
			HttpServletRequest request,
			HttpServletResponse response)
			throws ConfigurationException, IOException, ObjectWithIdNotFoundException {
		streaming.asJson2(testObjectTypeDao, request, response, new SimpleFilter(offset, limit));
	}

	@ApiOperation(value = "Get multiple Test Object Types as XML", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
			SERVICE_CAP_TAG_NAME}, produces = "text/xml")
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Object Types")
	})
	@RequestMapping(value = {TEST_OBJECT_TYPES_URL + ".xml"}, method = RequestMethod.GET)
	public void listTestObjectTypesXml(
			@RequestParam(required = false, defaultValue = "0") int offset,
			@RequestParam(required = false, defaultValue = "0") int limit,
			HttpServletRequest request,
			HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
		streaming.asXml2(testObjectTypeDao, request, response, new SimpleFilter(offset, limit));
	}

	@ApiOperation(value = "Get Test Object Type as XML", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
			SERVICE_CAP_TAG_NAME}, produces = "text/xml")
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "Test Object Type"),
			@ApiResponse(code = 404, message = "Test Object Type not found")
	})
	@RequestMapping(value = {TEST_OBJECT_TYPES_URL + "/{id}.xml"}, method = RequestMethod.GET)
	public void testObjectTypesByIdXml(
			@ApiParam(value = EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
			HttpServletRequest request,
			HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
		streaming.asXml2(testObjectTypeDao, request, response, id);
	}
}
