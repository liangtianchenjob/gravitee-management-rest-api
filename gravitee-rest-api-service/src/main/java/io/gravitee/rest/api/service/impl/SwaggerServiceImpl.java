/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.rest.api.model.ImportSwaggerDescriptorEntity;
import io.gravitee.rest.api.model.ImportSwaggerDescriptorEntity.Format;
import io.gravitee.rest.api.model.api.SwaggerApiEntity;
import io.gravitee.rest.api.service.SwaggerService;
import io.gravitee.rest.api.service.exceptions.SwaggerDescriptorException;
import io.gravitee.rest.api.service.impl.swagger.converter.api.OAIToAPIConverter;
import io.gravitee.rest.api.service.impl.swagger.converter.api.SwaggerV2ToAPIConverter;
import io.gravitee.rest.api.service.impl.swagger.parser.OAIParser;
import io.gravitee.rest.api.service.impl.swagger.parser.SwaggerV1Parser;
import io.gravitee.rest.api.service.impl.swagger.parser.SwaggerV2Parser;
import io.gravitee.rest.api.service.impl.swagger.parser.WsdlParser;
import io.gravitee.rest.api.service.impl.swagger.policy.PolicyOperationVisitorManager;
import io.gravitee.rest.api.service.impl.swagger.transformer.SwaggerTransformer;
import io.gravitee.rest.api.service.impl.swagger.visitor.v2.SwaggerOperationVisitor;
import io.gravitee.rest.api.service.impl.swagger.visitor.v3.OAIOperationVisitor;
import io.gravitee.rest.api.service.sanitizer.UrlSanitizerUtils;
import io.gravitee.rest.api.service.spring.ImportConfiguration;
import io.gravitee.rest.api.service.swagger.OAIDescriptor;
import io.gravitee.rest.api.service.swagger.SwaggerDescriptor;
import io.gravitee.rest.api.service.swagger.SwaggerV1Descriptor;
import io.gravitee.rest.api.service.swagger.SwaggerV2Descriptor;
import io.swagger.models.Swagger;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class SwaggerServiceImpl implements SwaggerService {

    private final Logger logger = LoggerFactory.getLogger(SwaggerServiceImpl.class);

    @Value("${swagger.scheme:https}")
    private String defaultScheme;

    @Autowired
    private PolicyOperationVisitorManager policyOperationVisitorManager;

    @Autowired
    private ImportConfiguration importConfiguration;

    @Override
    public SwaggerApiEntity createAPI(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        boolean wsdlImport = Format.WSDL.equals(swaggerDescriptor.getFormat());
        SwaggerDescriptor descriptor = parse(swaggerDescriptor.getPayload(), wsdlImport);
        if (wsdlImport) {
            overridePayload(swaggerDescriptor, descriptor);
        }

        if (descriptor != null) {
            if (descriptor.getVersion() == SwaggerDescriptor.Version.SWAGGER_V1 || descriptor.getVersion() == SwaggerDescriptor.Version.SWAGGER_V2) {
                List<SwaggerOperationVisitor> visitors = policyOperationVisitorManager.getPolicyVisitors().stream()
                        .filter(operationVisitor -> swaggerDescriptor.getWithPolicies() != null
                                && swaggerDescriptor.getWithPolicies().contains(operationVisitor.getId()))
                        .map(operationVisitor -> policyOperationVisitorManager.getSwaggerOperationVisitor(operationVisitor.getId()))
                        .collect(Collectors.toList());
                return new SwaggerV2ToAPIConverter(visitors, defaultScheme)
                        .convert((SwaggerV2Descriptor) descriptor);
            } else if (descriptor.getVersion() == SwaggerDescriptor.Version.OAI_V3) {
                List<OAIOperationVisitor> visitors = policyOperationVisitorManager.getPolicyVisitors().stream()
                        .filter(operationVisitor -> swaggerDescriptor.getWithPolicies() != null
                                && swaggerDescriptor.getWithPolicies().contains(operationVisitor.getId()))
                        .map(operationVisitor -> policyOperationVisitorManager.getOAIOperationVisitor(operationVisitor.getId()))
                        .collect(Collectors.toList());

                return new OAIToAPIConverter(visitors)
                        .convert((OAIDescriptor) descriptor);
            }
        }

        throw new SwaggerDescriptorException();
    }

    /**
     * Override the payload attribute of swaggerDescriptor by the JSON representation of the descriptor.
     * This is useful in case of WSDL import to generate a swagger page instead of exposing the WSDL.
     *
     * @param swaggerDescriptor
     * @param descriptor
     */
    private void overridePayload(ImportSwaggerDescriptorEntity swaggerDescriptor, SwaggerDescriptor descriptor) {
        try {
            swaggerDescriptor.setPayload(descriptor.toYaml());
            swaggerDescriptor.setType(ImportSwaggerDescriptorEntity.Type.INLINE);
        } catch (JsonProcessingException e) {
            logger.debug("JSON serialization failed, unable to override payload attribute", e);
        }
    }

    @Override
    public <Y, T extends SwaggerDescriptor<Y>> void transform(T descriptor, Collection<SwaggerTransformer<T>> transformers) {
        if (transformers != null) {
            transformers.forEach(transformer -> transformer.transform(descriptor));
        }
    }

    @Override
    public SwaggerDescriptor parse(String content) {
        return parse(content, false);
    }

    public SwaggerDescriptor parse(String content, boolean wsdl) {
        Object descriptor;

        if (isUrl(content)) {
            UrlSanitizerUtils.checkAllowed(content, importConfiguration.getImportWhitelist(), importConfiguration.isAllowImportFromPrivate());
        }

        if (wsdl) {
            // try to read wsdl
            logger.debug("Trying to load a Wsdl descriptor");

            descriptor = new WsdlParser().parse(content);

            if (descriptor != null) {
                return new OAIDescriptor((OpenAPI) descriptor);
            }
        } else {
            // try to read swagger in version 2
            logger.debug("Trying to load a Swagger v2 descriptor");

            descriptor = new SwaggerV2Parser().parse(content);

            if (descriptor != null) {
                return new SwaggerV2Descriptor((Swagger) descriptor);
            }

            // try to read swagger in version 3 (openAPI)
            logger.debug("Trying to load an OpenAPI descriptor");
            descriptor = new OAIParser().parse(content);

            if (descriptor != null) {
                return new OAIDescriptor((OpenAPI) descriptor);
            }

            // try to read swagger in version 1
            logger.debug("Trying to load an old Swagger descriptor");
            descriptor = new SwaggerV1Parser().parse(content);

            if (descriptor != null) {
                return new SwaggerV1Descriptor((Swagger) descriptor);
            }
        }

        throw new SwaggerDescriptorException();
    }

    private boolean isUrl(String content) {

        try {
            new URL(content);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }
}