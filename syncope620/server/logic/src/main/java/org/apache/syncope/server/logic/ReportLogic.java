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
package org.apache.syncope.server.logic;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.apache.cocoon.optional.pipeline.components.sax.fop.FopSerializer;
import org.apache.cocoon.pipeline.NonCachingPipeline;
import org.apache.cocoon.pipeline.Pipeline;
import org.apache.cocoon.sax.SAXPipelineComponent;
import org.apache.cocoon.sax.component.XMLGenerator;
import org.apache.cocoon.sax.component.XMLSerializer;
import org.apache.cocoon.sax.component.XSLTTransformer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.syncope.common.lib.SyncopeClientException;
import org.apache.syncope.common.lib.report.ReportletConf;
import org.apache.syncope.common.lib.to.ReportExecTO;
import org.apache.syncope.common.lib.to.ReportTO;
import org.apache.syncope.common.lib.types.ClientExceptionType;
import org.apache.syncope.common.lib.types.ReportExecExportFormat;
import org.apache.syncope.common.lib.types.ReportExecStatus;
import org.apache.syncope.server.persistence.api.dao.NotFoundException;
import org.apache.syncope.server.persistence.api.dao.ReportDAO;
import org.apache.syncope.server.persistence.api.dao.ReportExecDAO;
import org.apache.syncope.server.persistence.api.dao.search.OrderByClause;
import org.apache.syncope.server.persistence.api.entity.EntityFactory;
import org.apache.syncope.server.persistence.api.entity.Report;
import org.apache.syncope.server.persistence.api.entity.ReportExec;
import org.apache.syncope.server.provisioning.api.data.ReportDataBinder;
import org.apache.syncope.server.provisioning.api.job.JobNamer;
import org.apache.syncope.server.logic.init.ImplementationClassNamesLoader;
import org.apache.syncope.server.logic.init.JobInstanceLoader;
import org.apache.syncope.server.logic.report.Reportlet;
import org.apache.syncope.server.logic.report.ReportletConfClass;
import org.apache.syncope.server.logic.report.TextSerializer;
import org.apache.xmlgraphics.util.MimeConstants;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

@Component
public class ReportLogic extends AbstractTransactionalLogic<ReportTO> {

    @Autowired
    private ReportDAO reportDAO;

    @Autowired
    private ReportExecDAO reportExecDAO;

    @Autowired
    private JobInstanceLoader jobInstanceLoader;

    @Autowired
    private SchedulerFactoryBean scheduler;

    @Autowired
    private ReportDataBinder binder;

    @Autowired
    private EntityFactory entityFactory;

    @Autowired
    private ImplementationClassNamesLoader classNamesLoader;

    @PreAuthorize("hasRole('REPORT_CREATE')")
    public ReportTO create(final ReportTO reportTO) {
        Report report = entityFactory.newEntity(Report.class);
        binder.getReport(report, reportTO);
        report = reportDAO.save(report);

        try {
            jobInstanceLoader.registerJob(report);
        } catch (Exception e) {
            LOG.error("While registering quartz job for report " + report.getKey(), e);

            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.Scheduling);
            sce.getElements().add(e.getMessage());
            throw sce;
        }

        return binder.getReportTO(report);
    }

    @PreAuthorize("hasRole('REPORT_UPDATE')")
    public ReportTO update(final ReportTO reportTO) {
        Report report = reportDAO.find(reportTO.getKey());
        if (report == null) {
            throw new NotFoundException("Report " + reportTO.getKey());
        }

        binder.getReport(report, reportTO);
        report = reportDAO.save(report);

        try {
            jobInstanceLoader.registerJob(report);
        } catch (Exception e) {
            LOG.error("While registering quartz job for report " + report.getKey(), e);

            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.Scheduling);
            sce.getElements().add(e.getMessage());
            throw sce;
        }

        return binder.getReportTO(report);
    }

    @PreAuthorize("hasRole('REPORT_LIST')")
    public int count() {
        return reportDAO.count();
    }

    @PreAuthorize("hasRole('REPORT_LIST')")
    public List<ReportTO> list(final int page, final int size, final List<OrderByClause> orderByClauses) {
        List<Report> reports = reportDAO.findAll(page, size, orderByClauses);
        List<ReportTO> result = new ArrayList<>(reports.size());
        for (Report report : reports) {
            result.add(binder.getReportTO(report));
        }
        return result;
    }

    private Class<? extends ReportletConf> getReportletConfClass(final Class<Reportlet> reportletClass) {
        Class<? extends ReportletConf> result = null;

        ReportletConfClass annotation = reportletClass.getAnnotation(ReportletConfClass.class);
        if (annotation != null) {
            result = annotation.value();
        }

        return result;
    }

    public Class<Reportlet> findReportletClassHavingConfClass(final Class<? extends ReportletConf> reportletConfClass) {
        Class<Reportlet> result = null;
        for (Class<Reportlet> reportletClass : getAllReportletClasses()) {
            Class<? extends ReportletConf> found = getReportletConfClass(reportletClass);
            if (found != null && found.equals(reportletConfClass)) {
                result = reportletClass;
            }
        }

        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Set<Class<Reportlet>> getAllReportletClasses() {
        Set<Class<Reportlet>> reportletClasses = new HashSet<>();

        for (String className : classNamesLoader.getClassNames(ImplementationClassNamesLoader.Type.REPORTLET)) {
            try {
                Class reportletClass = ClassUtils.forName(className, ClassUtils.getDefaultClassLoader());
                reportletClasses.add(reportletClass);
            } catch (ClassNotFoundException e) {
                LOG.warn("Could not load class {}", className);
            } catch (LinkageError e) {
                LOG.warn("Could not link class {}", className);
            }
        }
        return reportletClasses;
    }

    @PreAuthorize("hasRole('REPORT_LIST')")
    @SuppressWarnings("rawtypes")
    public Set<String> getReportletConfClasses() {
        Set<String> reportletConfClasses = new HashSet<>();

        for (Class<Reportlet> reportletClass : getAllReportletClasses()) {
            Class<? extends ReportletConf> reportletConfClass = getReportletConfClass(reportletClass);
            if (reportletConfClass != null) {
                reportletConfClasses.add(reportletConfClass.getName());
            }
        }

        return reportletConfClasses;
    }

    @PreAuthorize("hasRole('REPORT_READ')")
    public ReportTO read(final Long reportId) {
        Report report = reportDAO.find(reportId);
        if (report == null) {
            throw new NotFoundException("Report " + reportId);
        }
        return binder.getReportTO(report);
    }

    @PreAuthorize("hasRole('REPORT_READ')")
    @Transactional(readOnly = true)
    public ReportExecTO readExecution(final Long executionId) {
        ReportExec reportExec = reportExecDAO.find(executionId);
        if (reportExec == null) {
            throw new NotFoundException("Report execution " + executionId);
        }
        return binder.getReportExecTO(reportExec);
    }

    @PreAuthorize("hasRole('REPORT_READ')")
    public void exportExecutionResult(final OutputStream os, final ReportExec reportExec,
            final ReportExecExportFormat format) {

        // streaming SAX handler from a compressed byte array stream
        ByteArrayInputStream bais = new ByteArrayInputStream(reportExec.getExecResult());
        ZipInputStream zis = new ZipInputStream(bais);
        try {
            // a single ZipEntry in the ZipInputStream (see ReportJob)
            zis.getNextEntry();

            Pipeline<SAXPipelineComponent> pipeline = new NonCachingPipeline<SAXPipelineComponent>();
            pipeline.addComponent(new XMLGenerator(zis));

            Map<String, Object> parameters = new HashMap<String, Object>();
            parameters.put("status", reportExec.getStatus());
            parameters.put("message", reportExec.getMessage());
            parameters.put("startDate", reportExec.getStartDate());
            parameters.put("endDate", reportExec.getEndDate());

            switch (format) {
                case HTML:
                    XSLTTransformer xsl2html = new XSLTTransformer(getClass().getResource("/report/report2html.xsl"));
                    xsl2html.setParameters(parameters);
                    pipeline.addComponent(xsl2html);
                    pipeline.addComponent(XMLSerializer.createXHTMLSerializer());
                    break;

                case PDF:
                    XSLTTransformer xsl2pdf = new XSLTTransformer(getClass().getResource("/report/report2fo.xsl"));
                    xsl2pdf.setParameters(parameters);
                    pipeline.addComponent(xsl2pdf);
                    pipeline.addComponent(new FopSerializer(MimeConstants.MIME_PDF));
                    break;

                case RTF:
                    XSLTTransformer xsl2rtf = new XSLTTransformer(getClass().getResource("/report/report2fo.xsl"));
                    xsl2rtf.setParameters(parameters);
                    pipeline.addComponent(xsl2rtf);
                    pipeline.addComponent(new FopSerializer(MimeConstants.MIME_RTF));
                    break;

                case CSV:
                    XSLTTransformer xsl2csv = new XSLTTransformer(getClass().getResource("/report/report2csv.xsl"));
                    xsl2csv.setParameters(parameters);
                    pipeline.addComponent(xsl2csv);
                    pipeline.addComponent(new TextSerializer());
                    break;

                case XML:
                default:
                    pipeline.addComponent(XMLSerializer.createXMLSerializer());
            }

            pipeline.setup(os);
            pipeline.execute();

            LOG.debug("Result of {} successfully exported as {}", reportExec, format);
        } catch (Exception e) {
            LOG.error("While exporting content", e);
        } finally {
            IOUtils.closeQuietly(zis);
            IOUtils.closeQuietly(bais);
        }
    }

    @PreAuthorize("hasRole('REPORT_READ')")
    public ReportExec getAndCheckReportExec(final Long executionId) {
        ReportExec reportExec = reportExecDAO.find(executionId);
        if (reportExec == null) {
            throw new NotFoundException("Report execution " + executionId);
        }
        if (!ReportExecStatus.SUCCESS.name().equals(reportExec.getStatus()) || reportExec.getExecResult() == null) {
            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.InvalidReportExec);
            sce.getElements().add(reportExec.getExecResult() == null
                    ? "No report data produced"
                    : "Report did not run successfully");
            throw sce;
        }
        return reportExec;
    }

    @PreAuthorize("hasRole('REPORT_EXECUTE')")
    public ReportExecTO execute(final Long reportId) {
        Report report = reportDAO.find(reportId);
        if (report == null) {
            throw new NotFoundException("Report " + reportId);
        }

        try {
            jobInstanceLoader.registerJob(report);

            scheduler.getScheduler().triggerJob(
                    new JobKey(JobNamer.getJobName(report), Scheduler.DEFAULT_GROUP));
        } catch (Exception e) {
            LOG.error("While executing report {}", report, e);

            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.Scheduling);
            sce.getElements().add(e.getMessage());
            throw sce;
        }

        ReportExecTO result = new ReportExecTO();
        result.setReport(reportId);
        result.setStartDate(new Date());
        result.setStatus(ReportExecStatus.STARTED.name());
        result.setMessage("Job fired; waiting for results...");

        return result;
    }

    @PreAuthorize("hasRole('REPORT_DELETE')")
    public ReportTO delete(final Long reportId) {
        Report report = reportDAO.find(reportId);
        if (report == null) {
            throw new NotFoundException("Report " + reportId);
        }

        ReportTO deletedReport = binder.getReportTO(report);
        jobInstanceLoader.unregisterJob(report);
        reportDAO.delete(report);
        return deletedReport;
    }

    @PreAuthorize("hasRole('REPORT_DELETE')")
    public ReportExecTO deleteExecution(final Long executionId) {
        ReportExec reportExec = reportExecDAO.find(executionId);
        if (reportExec == null) {
            throw new NotFoundException("Report execution " + executionId);
        }

        ReportExecTO reportExecToDelete = binder.getReportExecTO(reportExec);
        reportExecDAO.delete(reportExec);
        return reportExecToDelete;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ReportTO resolveReference(final Method method, final Object... args)
            throws UnresolvedReferenceException {

        Long id = null;

        if (ArrayUtils.isNotEmpty(args) && ("create".equals(method.getName())
                || "update".equals(method.getName())
                || "delete".equals(method.getName()))) {
            for (int i = 0; id == null && i < args.length; i++) {
                if (args[i] instanceof Long) {
                    id = (Long) args[i];
                } else if (args[i] instanceof ReportTO) {
                    id = ((ReportTO) args[i]).getKey();
                }
            }
        }

        if ((id != null) && !id.equals(0l)) {
            try {
                return binder.getReportTO(reportDAO.find(id));
            } catch (Throwable ignore) {
                LOG.debug("Unresolved reference", ignore);
                throw new UnresolvedReferenceException(ignore);
            }
        }

        throw new UnresolvedReferenceException();
    }
}
