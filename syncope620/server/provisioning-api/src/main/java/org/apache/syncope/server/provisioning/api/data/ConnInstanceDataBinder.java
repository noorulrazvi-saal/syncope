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
package org.apache.syncope.server.provisioning.api.data;

import java.util.Set;
import org.apache.syncope.common.lib.to.ConnInstanceTO;
import org.apache.syncope.common.lib.types.ConnConfPropSchema;
import org.apache.syncope.common.lib.types.ConnConfProperty;
import org.apache.syncope.server.persistence.api.entity.ConnInstance;
import org.identityconnectors.framework.api.ConfigurationProperty;

public interface ConnInstanceDataBinder {

    ConnConfPropSchema buildConnConfPropSchema(ConfigurationProperty property);

    ConnInstance getConnInstance(ConnInstanceTO connInstanceTO);

    ConnInstanceTO getConnInstanceTO(ConnInstance connInstance);

    /**
     * Merge connector configuration properties avoiding repetition but giving priority to primary set.
     *
     * @param primary primary set.
     * @param secondary secondary set.
     * @return merged set.
     */
    Set<ConnConfProperty> mergeConnConfProperties(Set<ConnConfProperty> primary,
            Set<ConnConfProperty> secondary);

    ConnInstance updateConnInstance(long connInstanceId, ConnInstanceTO connInstanceTO);

}
