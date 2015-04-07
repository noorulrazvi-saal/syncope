/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.persistence.jpa.dao;

import java.util.List;
import javax.persistence.TypedQuery;
import org.apache.commons.collections4.Closure;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.syncope.common.lib.types.AttributableType;
import org.apache.syncope.core.persistence.api.dao.AttrTemplateDAO;
import org.apache.syncope.core.persistence.api.dao.ExternalResourceDAO;
import org.apache.syncope.core.persistence.api.dao.VirAttrDAO;
import org.apache.syncope.core.persistence.api.dao.VirSchemaDAO;
import org.apache.syncope.core.persistence.api.entity.AttributableUtil;
import org.apache.syncope.core.persistence.api.entity.VirAttr;
import org.apache.syncope.core.persistence.api.entity.VirSchema;
import org.apache.syncope.core.persistence.api.entity.membership.MVirSchema;
import org.apache.syncope.core.persistence.api.entity.group.GVirSchema;
import org.apache.syncope.core.persistence.api.entity.user.UMappingItem;
import org.apache.syncope.core.persistence.api.entity.user.UVirAttr;
import org.apache.syncope.core.persistence.api.entity.user.UVirSchema;
import org.apache.syncope.core.persistence.jpa.entity.AbstractVirSchema;
import org.apache.syncope.core.persistence.jpa.entity.membership.JPAMVirSchema;
import org.apache.syncope.core.persistence.jpa.entity.group.JPAGVirSchema;
import org.apache.syncope.core.persistence.jpa.entity.user.JPAUVirSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class JPAVirSchemaDAO extends AbstractDAO<VirSchema, String> implements VirSchemaDAO {

    @Autowired
    private VirAttrDAO virAttrDAO;

    @Autowired
    private AttrTemplateDAO<VirSchema> attrTemplateDAO;

    @Autowired
    private ExternalResourceDAO resourceDAO;

    private <T extends VirSchema> Class<? extends AbstractVirSchema> getJPAEntityReference(final Class<T> reference) {
        return GVirSchema.class.isAssignableFrom(reference)
                ? JPAGVirSchema.class
                : MVirSchema.class.isAssignableFrom(reference)
                        ? JPAMVirSchema.class
                        : UVirSchema.class.isAssignableFrom(reference)
                                ? JPAUVirSchema.class
                                : null;
    }

    @Override
    public <T extends VirSchema> T find(final String key, final Class<T> reference) {
        return reference.cast(entityManager.find(getJPAEntityReference(reference), key));
    }

    @Override
    public <T extends VirSchema> List<T> findAll(final Class<T> reference) {
        TypedQuery<T> query = entityManager.createQuery(
                "SELECT e FROM " + getJPAEntityReference(reference).getSimpleName() + " e", reference);
        return query.getResultList();
    }

    @Override
    public <T extends VirAttr> List<T> findAttrs(final VirSchema schema, final Class<T> reference) {
        final StringBuilder queryString = new StringBuilder("SELECT e FROM ").
                append(((JPAVirAttrDAO) virAttrDAO).getJPAEntityReference(reference).getSimpleName()).
                append(" e WHERE e.");
        if (UVirAttr.class.isAssignableFrom(reference)) {
            queryString.append("virSchema");
        } else {
            queryString.append("template.schema");
        }
        queryString.append("=:schema");

        TypedQuery<T> query = entityManager.createQuery(queryString.toString(), reference);
        query.setParameter("schema", schema);

        return query.getResultList();
    }

    @Override
    public <T extends VirSchema> T save(final T virSchema) {
        return entityManager.merge(virSchema);
    }

    @Override
    public void delete(final String key, final AttributableUtil attributableUtil) {
        final VirSchema schema = find(key, attributableUtil.virSchemaClass());
        if (schema == null) {
            return;
        }

        CollectionUtils.forAllDo(findAttrs(schema, attributableUtil.virAttrClass()), new Closure<VirAttr>() {

            @Override
            public void execute(final VirAttr input) {
                virAttrDAO.delete(input.getKey(), attributableUtil.virAttrClass());
            }

        });

        if (attributableUtil.getType() != AttributableType.USER) {
            CollectionUtils.forAllDo(attrTemplateDAO.
                    findBySchemaName(schema.getKey(), attributableUtil.virAttrTemplateClass()).iterator(),
                    new Closure<Number>() {

                        @Override
                        public void execute(final Number input) {
                            attrTemplateDAO.delete(input.longValue(), attributableUtil.virAttrTemplateClass());
                        }

                    });
        }

        resourceDAO.deleteMapping(key, attributableUtil.virIntMappingType(), UMappingItem.class);

        entityManager.remove(schema);
    }
}
