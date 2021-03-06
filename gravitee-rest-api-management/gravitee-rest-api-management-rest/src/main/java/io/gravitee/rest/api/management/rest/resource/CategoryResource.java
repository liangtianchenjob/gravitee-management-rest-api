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
package io.gravitee.rest.api.management.rest.resource;

import io.gravitee.common.http.MediaType;
import io.gravitee.rest.api.exception.InvalidImageException;
import io.gravitee.rest.api.model.InlinePictureEntity;
import io.gravitee.rest.api.model.UpdateCategoryEntity;
import io.gravitee.rest.api.model.CategoryEntity;
import io.gravitee.rest.api.model.permissions.RolePermission;
import io.gravitee.rest.api.model.permissions.RolePermissionAction;
import io.gravitee.rest.api.management.rest.security.Permission;
import io.gravitee.rest.api.management.rest.security.Permissions;
import io.gravitee.rest.api.security.utils.ImageUtils;
import io.gravitee.rest.api.service.CategoryService;
import io.gravitee.rest.api.service.exceptions.UnauthorizedAccessException;
import io.gravitee.rest.api.service.exceptions.CategoryNotFoundException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import org.springframework.beans.factory.annotation.Autowired;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.ByteArrayOutputStream;

import static io.gravitee.common.http.MediaType.APPLICATION_JSON;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
@Api(tags = {"Category"})
public class CategoryResource extends AbstractCategoryResource {

    @Autowired
    private CategoryService categoryService;

    @GET
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get the Category",
            notes = "User must have the READ permission to use this service")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Category's definition", response = CategoryEntity.class),
            @ApiResponse(code = 500, message = "Internal server error")})
    public CategoryEntity get(@PathParam("id") String categoryId) {
        boolean canShowCategory = hasPermission(RolePermission.ENVIRONMENT_CATEGORY, RolePermissionAction.READ);
        CategoryEntity category = categoryService.findById(categoryId);

        if (!canShowCategory && category.isHidden()) {
            throw new UnauthorizedAccessException();
        }

        // set picture
        setPictures(category, false);
        return category;
    }

    @GET
    @Path("picture")
    @ApiOperation(value = "Get the Category's picture",
            notes = "User must have the READ permission to use this service")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Category's picture"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public Response picture(
            @Context Request request,
            @PathParam("id") String categoryId) throws CategoryNotFoundException {
        return getImageResponse(request, categoryId, categoryService.getPicture(categoryId));
    }

    @GET
    @Path("background")
    @ApiOperation(value = "Get the Category's background",
            notes = "User must have the READ permission to use this service")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Category's background"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public Response background(
            @Context Request request,
            @PathParam("id") String categoryId) throws CategoryNotFoundException {
        return getImageResponse(request, categoryId, categoryService.getBackground(categoryId));
    }

    private Response getImageResponse(Request request, String categoryId, InlinePictureEntity image) {
        boolean canShowCategory = hasPermission(RolePermission.ENVIRONMENT_CATEGORY, RolePermissionAction.READ);
        CategoryEntity category = categoryService.findById(categoryId);

        if (!canShowCategory && category.isHidden()) {
            throw new UnauthorizedAccessException();
        }

        CacheControl cc = new CacheControl();
        cc.setNoTransform(true);
        cc.setMustRevalidate(false);
        cc.setNoCache(false);
        cc.setMaxAge(86400);

        if (image == null || image.getContent() == null) {
            return Response.ok().build();
        }

        EntityTag etag = new EntityTag(Integer.toString(new String(image.getContent()).hashCode()));
        Response.ResponseBuilder builder = request.evaluatePreconditions(etag);

        if (builder != null) {
            // Preconditions are not met, returning HTTP 304 'not-modified'
            return builder
                    .cacheControl(cc)
                    .build();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(image.getContent(), 0, image.getContent().length);

        return Response
                .ok(baos)
                .cacheControl(cc)
                .tag(etag)
                .type(image.getType())
                .build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Update the Category",
            notes = "User must have the UPDATE permission to use this service")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Category successfully updated", response = CategoryEntity.class),
            @ApiResponse(code = 500, message = "Internal server error")})
    @Permissions({
            @Permission(value = RolePermission.ENVIRONMENT_CATEGORY, acls = RolePermissionAction.UPDATE)
    })
    public Response update(@PathParam("id") String categoryId, @Valid @NotNull final UpdateCategoryEntity category) {
        try {
            ImageUtils.verify(category.getPicture());
            ImageUtils.verify(category.getBackground());
        } catch (InvalidImageException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid image format").build();
        }

        CategoryEntity categoryEntity = categoryService.update(categoryId, category);
        setPictures(categoryEntity, false);

        return Response.ok(categoryEntity).build();
    }

    @DELETE
    @ApiOperation(
            value = "Delete the Category",
            notes = "User must have the DELETE permission to use this service")
    @ApiResponses({
            @ApiResponse(code = 204, message = "Category successfully deleted"),
            @ApiResponse(code = 500, message = "Internal server error")})
    @Permissions({
            @Permission(value = RolePermission.ENVIRONMENT_CATEGORY, acls = RolePermissionAction.DELETE)
    })
    public void delete(@PathParam("id") String id) {
        categoryService.delete(id);
    }

}
