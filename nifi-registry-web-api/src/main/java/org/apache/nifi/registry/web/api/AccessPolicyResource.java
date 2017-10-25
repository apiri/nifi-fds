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
package org.apache.nifi.registry.web.api;


import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.nifi.registry.authorization.Authorizer;
import org.apache.nifi.registry.authorization.AuthorizerCapabilityDetection;
import org.apache.nifi.registry.authorization.RequestAction;
import org.apache.nifi.registry.authorization.resource.Authorizable;
import org.apache.nifi.registry.authorization.user.NiFiUserUtils;
import org.apache.nifi.registry.model.authorization.AccessPolicy;
import org.apache.nifi.registry.model.authorization.AccessPolicySummary;
import org.apache.nifi.registry.service.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * RESTful endpoint for managing access policies.
 */
@Component
@Path("/policies")
@Api(
        value = "/policies",
        description = "Endpoint for managing access policies."
)
public class AccessPolicyResource extends AuthorizableApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(AccessPolicyResource.class);

    @Autowired
    public AccessPolicyResource(
            Authorizer authorizer,
            AuthorizationService authorizationService) {
        super(authorizer, authorizationService);
    }

    // TODO - Verify that access control is done by the resource the policy is for, not the /policies resource itself.

    /**
     * Create a new access policy.
     *
     * @param httpServletRequest request
     * @param requestAccessPolicy the access policy to create.
     * @return The created access policy.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Creates an access policy",
            response = AccessPolicy.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response createAccessPolicy(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(value = "The access policy configuration details.", required = true)
            final AccessPolicy requestAccessPolicy) {

        verifyAuthorizerSupportsConfigurablePolicies();

        if (requestAccessPolicy == null) {
            throw new IllegalArgumentException("Access policy details must be specified when creating a new policy.");
        }
        if (requestAccessPolicy.getIdentifier() != null) {
            throw new IllegalArgumentException("Access policy ID cannot be specified when creating a new policy.");
        }
        if (requestAccessPolicy.getResource() == null) {
            throw new IllegalArgumentException("Resource must be specified when creating a new access policy.");
        }
        RequestAction.valueOfValue(requestAccessPolicy.getAction());

        authorizeAccessToResource(RequestAction.WRITE, requestAccessPolicy.getResource());

        AccessPolicy createdPolicy = authorizationService.createAccessPolicy(requestAccessPolicy);

        String locationUri = generateAccessPolicyUri(createdPolicy);
        return generateCreatedResponse(URI.create(locationUri), createdPolicy).build();
    }

    /**
     * Retrieves all access policies
     *
     * @return A list of access policies
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Gets all access policies",
            response = AccessPolicy.class,
            responseContainer = "List"
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_409),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_409) })
    public Response getAccessPolicies() {

        verifyAuthorizerIsManaged();
        authorizeAccess(RequestAction.READ);

        final List<AccessPolicy> accessPolicies = authorizationService.getAccessPolicies();

        return generateOkResponse(accessPolicies).build();
    }

    /**
     * Retrieves the specified access policy.
     *
     * @param identifier The id of the access policy to retrieve
     * @return An accessPolicyEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Gets an access policy",
            response = AccessPolicy.class
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404) })
    public Response getAccessPolicy(
            @ApiParam(value = "The access policy id.", required = true)
            @PathParam("id") final String identifier) {

        verifyAuthorizerIsManaged();

        final AccessPolicy accessPolicy = authorizationService.getAccessPolicy(identifier);
        authorizeAccessToResource(RequestAction.READ, accessPolicy.getResource());

        return generateOkResponse(accessPolicy).build();
    }


    /**
     * Retrieve a specified access policy for a given (action, resource) pair.
     *
     * @param action the action, i.e. "read", "write"
     * @param rawResource the name of the resource as a raw string
     * @return An access policy.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{action}/{resource: .+}")
    @ApiOperation(
            value = "Gets an access policy for the specified action and resource",
            notes = "Will return the effective policy if no specific policy exists for the specified action and resource. "
                    + "Must have Read permissions to the policy with the desired action and resource. Permissions for the policy that is "
                    + "returned will be indicated in the response. If the client does not have permissions to that policy, the response "
                    + "will not include the policy and the permissions in the response will be marked accordingly. If the client does "
                    + "not have permissions to the policy of the desired action and resource a 403 response will be returned.",
            response = AccessPolicy.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getAccessPolicyForResource(
            @ApiParam(value = "The request action.", allowableValues = "read, write, delete", required = true)
            @PathParam("action")
            final String action,
            @ApiParam(value = "The resource of the policy.", required = true)
            @PathParam("resource")
            final String rawResource) {

        verifyAuthorizerIsManaged();

        // parse the action and resource type
        final RequestAction requestAction = RequestAction.valueOfValue(action);
        final String resource = "/" + rawResource;

        authorizeAccessToResource(RequestAction.READ, resource);

        AccessPolicy accessPolicy = authorizationService.getAccessPolicy(resource, requestAction);
        return generateOkResponse(accessPolicy).build();
    }


    /**
     * Update an access policy.
     *
     * @param httpServletRequest request
     * @param identifier         The id of the access policy to update.
     * @param requestAccessPolicy An access policy.
     * @return the updated access policy.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Updates a access policy",
            response = AccessPolicy.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response updateAccessPolicy(
            @Context
            final HttpServletRequest httpServletRequest,
            @ApiParam(value = "The access policy id.", required = true)
            @PathParam("id")
            final String identifier,
            @ApiParam(value = "The access policy configuration details.", required = true)
            final AccessPolicy requestAccessPolicy) {

        verifyAuthorizerSupportsConfigurablePolicies();

        if (requestAccessPolicy == null) {
            throw new IllegalArgumentException("Access policy details must be specified when updating a policy.");
        }
        if (!identifier.equals(requestAccessPolicy.getIdentifier())) {
            throw new IllegalArgumentException(String.format("The policy id in the request body (%s) does not equal the "
                    + "policy id of the requested resource (%s).", requestAccessPolicy.getIdentifier(), identifier));
        }

        authorizeAccessToPolicy(RequestAction.WRITE, identifier);

        AccessPolicy createdPolicy = authorizationService.createAccessPolicy(requestAccessPolicy);

        String locationUri = generateAccessPolicyUri(createdPolicy);
        return generateCreatedResponse(URI.create(locationUri), createdPolicy).build();
    }


    /**
     * Remove a specified access policy.
     *
     * @param httpServletRequest request
     * @param identifier         The id of the access policy to remove.
     * @return The deleted access policy
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Deletes an access policy",
            response = AccessPolicy.class
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found.") })
    public Response removeAccessPolicy(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(value = "The access policy id.", required = true)
            @PathParam("id")
            final String identifier) {

        verifyAuthorizerSupportsConfigurablePolicies();
        authorizeAccessToPolicy(RequestAction.DELETE, identifier);
        AccessPolicy deletedPolicy = authorizationService.deleteAccessPolicy(identifier);
        return generateOkResponse(deletedPolicy).build();
    }


    private void verifyAuthorizerIsManaged() {
        if (!AuthorizerCapabilityDetection.isManagedAuthorizer(authorizer)) {
            throw new IllegalStateException(AuthorizationService.MSG_NON_MANAGED_AUTHORIZER);
        }
    }

    private void verifyAuthorizerSupportsConfigurablePolicies() {
        if (!AuthorizerCapabilityDetection.isConfigurableAccessPolicyProvider(authorizer)) {
            throw new IllegalStateException(AuthorizationService.MSG_NON_CONFIGURABLE_POLICIES);
        }
    }

    private void authorizeAccess(RequestAction actionType) {
        authorizationService.authorizeAccess(lookup -> {
            final Authorizable policiesAuthorizable = lookup.getPoliciesAuthorizable();
            policiesAuthorizable.authorize(authorizer, actionType, NiFiUserUtils.getNiFiUser());
        });
    }

    private void authorizeAccessToPolicy(RequestAction actionType, String accessPolicyIdentifier) {
        AccessPolicy accessPolicy = authorizationService.getAccessPolicy(accessPolicyIdentifier);
        authorizeAccessToResource(actionType, accessPolicy.getResource());
    }

    private void authorizeAccessToResource(RequestAction actionType, String resource) {
        authorizationService.authorizeAccess(lookup -> {
            final Authorizable accessPolicy = lookup.getAccessPolicyByResource(resource);
            accessPolicy.authorize(authorizer, actionType, NiFiUserUtils.getNiFiUser());
        });
    }

    private String generateAccessPolicyUri(final AccessPolicySummary accessPolicy) {
        return generateResourceUri("policies", accessPolicy.getIdentifier());
    }

}