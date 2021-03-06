/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.jersey.server.internal.monitoring;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;

import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.model.ResourceModel;
import org.glassfish.jersey.server.monitoring.ApplicationStatistics;
import org.glassfish.jersey.server.monitoring.ExceptionMapperStatistics;
import org.glassfish.jersey.server.monitoring.ExecutionStatistics;
import org.glassfish.jersey.server.monitoring.MonitoringStatistics;
import org.glassfish.jersey.server.monitoring.ResourceStatistics;
import org.glassfish.jersey.server.monitoring.ResponseStatistics;

import jersey.repackaged.com.google.common.base.Function;
import jersey.repackaged.com.google.common.collect.Maps;

/**
 * Monitoring statistics implementation.
 *
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 */
final class MonitoringStatisticsImpl implements MonitoringStatistics {

    /**
     * Builder of monitoring statistics.
     */
    static class Builder {

        private static final Function<ResourceStatisticsImpl.Builder, ResourceStatistics> BUILDING_FUNCTION =
                new Function<ResourceStatisticsImpl.Builder, ResourceStatistics>() {
                    @Override
                    public ResourceStatistics apply(final ResourceStatisticsImpl.Builder builder) {
                        return builder.build();
                    }
                };

        private final ResponseStatisticsImpl.Builder responseStatisticsBuilder;
        private final ExceptionMapperStatisticsImpl.Builder exceptionMapperStatisticsBuilder;

        private final ResourceMethodStatisticsImpl.Factory methodFactory = new ResourceMethodStatisticsImpl.Factory();
        private final SortedMap<String, ResourceStatisticsImpl.Builder> uriStatistics = Maps.newTreeMap();
        private final SortedMap<Class<?>, ResourceStatisticsImpl.Builder> resourceClassStatistics
                = Maps.newTreeMap(new Comparator<Class<?>>() {
            @Override
            public int compare(final Class<?> o1, final Class<?> o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });

        private ExecutionStatisticsImpl.Builder requestStatisticsBuilder;
        private ApplicationStatisticsImpl applicationStatisticsImpl;

        /**
         * Create a new builder.
         */
        Builder() {
            this.responseStatisticsBuilder = new ResponseStatisticsImpl.Builder();
            this.exceptionMapperStatisticsBuilder = new ExceptionMapperStatisticsImpl.Builder();
        }

        /**
         * Create a new builder and initialize it from resource model.
         * @param resourceModel resource model.
         */
        Builder(final ResourceModel resourceModel) {
            this();

            for (final Resource resource : resourceModel.getRootResources()) {
                processResource(resource, "");
                for (final Resource child : resource.getChildResources()) {
                    processResource(child, "/" + resource.getPath());
                }
            }

        }

        private void processResource(final Resource resource, final String pathPrefix) {
            uriStatistics.put(pathPrefix + "/" + resource.getPath(), new ResourceStatisticsImpl.Builder(resource, methodFactory));

            for (final ResourceMethod resourceMethod : resource.getResourceMethods()) {
                getOrCreateResourceBuilder(resourceMethod).addMethod(resourceMethod);
            }
        }

        private ResourceStatisticsImpl.Builder getOrCreateResourceBuilder(final ResourceMethod resourceMethod) {
            final Class<?> clazz = resourceMethod.getInvocable().getHandler().getHandlerClass();
            ResourceStatisticsImpl.Builder builder = resourceClassStatistics.get(clazz);
            if (builder == null) {
                builder = new ResourceStatisticsImpl.Builder(methodFactory);
                resourceClassStatistics.put(clazz, builder);
            }
            return builder;
        }

        /**
         * Get the exception mapper statistics builder.
         * @return Builder of internal exception mapper statistics.
         */
        ExceptionMapperStatisticsImpl.Builder getExceptionMapperStatisticsBuilder() {
            return exceptionMapperStatisticsBuilder;
        }

        /**
         * Add global request execution.
         *
         * @param startTime time of the execution.
         * @param duration duration of the execution.
         */
        void addRequestExecution(final long startTime, final long duration) {
            if (requestStatisticsBuilder == null) {
                requestStatisticsBuilder = new ExecutionStatisticsImpl.Builder();
            }
            requestStatisticsBuilder.addExecution(startTime, duration);
        }

        /**
         * Add execution of a resource method.
         *
         * @param uri String uri which was executed.
         * @param resourceMethod Resource method.
         * @param methodTime Time spent on execution of resource method itself (Unix timestamp format).
         * @param methodDuration Time of execution of the resource method.
         * @param requestTime Time of whole request processing (from receiving
         *                    the request until writing the response). (Unix timestamp format)
         * @param requestDuration Time when the request matching to the executed resource method has been received
         *                         by Jersey.
         */
        void addExecution(final String uri, final ResourceMethod resourceMethod,
                          final long methodTime, final long methodDuration,
                          final long requestTime, final long requestDuration) {
            // Uri resource stats.
            ResourceStatisticsImpl.Builder uriStatsBuilder = uriStatistics.get(uri);
            if (uriStatsBuilder == null) {
                uriStatsBuilder = new ResourceStatisticsImpl.Builder(resourceMethod.getParent(), methodFactory);
                uriStatistics.put(uri, uriStatsBuilder);
            }
            uriStatsBuilder.addExecution(resourceMethod, methodTime, methodDuration, requestTime, requestDuration);

            // Class resource stats.
            final ResourceStatisticsImpl.Builder classStatsBuilder = getOrCreateResourceBuilder(resourceMethod);
            classStatsBuilder.addExecution(resourceMethod, methodTime, methodDuration, requestTime, requestDuration);

            // Resource method stats.
            methodFactory.getOrCreate(resourceMethod)
                    .addResourceMethodExecution(methodTime, methodDuration, requestTime, requestDuration);
        }


        /**
         * Add a response status code produces by Jersey.
         * @param responseCode Response status code.
         */
        void addResponseCode(final int responseCode) {
            responseStatisticsBuilder.addResponseCode(responseCode);
        }


        /**
         * Set the application statistics.
         * @param applicationStatisticsImpl Application statistics.
         */
        void setApplicationStatisticsImpl(final ApplicationStatisticsImpl applicationStatisticsImpl) {
            this.applicationStatisticsImpl = applicationStatisticsImpl;
        }

        /**
         * Build a new instance of monitoring statistics.
         * @return New instance of {@code MonitoringStatisticsImpl}.
         */
        MonitoringStatisticsImpl build() {
            final Map<String, ResourceStatistics> uriStats = Collections.unmodifiableMap(
                    Maps.transformValues(uriStatistics, BUILDING_FUNCTION));
            final Map<Class<?>, ResourceStatistics> classStats = Collections.unmodifiableMap(
                    Maps.transformValues(resourceClassStatistics, BUILDING_FUNCTION));

            final ExecutionStatistics requestStats = requestStatisticsBuilder == null ?
                    ExecutionStatisticsImpl.EMPTY : requestStatisticsBuilder.build();

            return new MonitoringStatisticsImpl(
                    uriStats, classStats, requestStats,
                    responseStatisticsBuilder.build(),
                    applicationStatisticsImpl,
                    exceptionMapperStatisticsBuilder.build());
        }
    }

    private final ExecutionStatistics requestStatistics;
    private final ResponseStatistics responseStatistics;
    private final ApplicationStatistics applicationStatistics;
    private final ExceptionMapperStatistics exceptionMapperStatistics;
    private final Map<String, ResourceStatistics> uriStatistics;
    private final Map<Class<?>, ResourceStatistics> resourceClassStatistics;


    private MonitoringStatisticsImpl(final Map<String, ResourceStatistics> uriStatistics,
                                     final Map<Class<?>, ResourceStatistics> resourceClassStatistics,
                                     final ExecutionStatistics requestStatistics,
                                     final ResponseStatistics responseStatistics,
                                     final ApplicationStatistics applicationStatistics,
                                     final ExceptionMapperStatistics exceptionMapperStatistics) {
        this.uriStatistics = uriStatistics;
        this.resourceClassStatistics = resourceClassStatistics;
        this.requestStatistics = requestStatistics;
        this.responseStatistics = responseStatistics;
        this.applicationStatistics = applicationStatistics;
        this.exceptionMapperStatistics = exceptionMapperStatistics;
    }


    @Override
    public ExecutionStatistics getRequestStatistics() {
        return requestStatistics;
    }


    @Override
    public ResponseStatistics getResponseStatistics() {
        return responseStatistics;
    }


    @Override
    public Map<String, ResourceStatistics> getUriStatistics() {
        return uriStatistics;
    }

    @Override
    public Map<Class<?>, ResourceStatistics> getResourceClassStatistics() {
        return resourceClassStatistics;
    }

    @Override
    public ApplicationStatistics getApplicationStatistics() {
        return applicationStatistics;
    }

    @Override
    public ExceptionMapperStatistics getExceptionMapperStatistics() {
        return exceptionMapperStatistics;
    }

    @Override
    public MonitoringStatistics snapshot() {
        // snapshot functionality not yet implemented
        return this;
    }
}
