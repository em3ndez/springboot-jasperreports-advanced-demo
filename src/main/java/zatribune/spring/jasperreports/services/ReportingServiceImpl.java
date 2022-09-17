package zatribune.spring.jasperreports.services;


import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.data.JsonDataSource;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import zatribune.spring.jasperreports.config.ResourcesLoader;
import zatribune.spring.jasperreports.db.entities.Report;
import zatribune.spring.jasperreports.db.entities.ReportLocale;
import zatribune.spring.jasperreports.errors.BadReportEntryException;
import zatribune.spring.jasperreports.errors.UnsupportedItemException;
import zatribune.spring.jasperreports.model.ReportExportType;
import zatribune.spring.jasperreports.model.ReportRequest;
import zatribune.spring.jasperreports.translate.Translator;
import zatribune.spring.jasperreports.utils.processor.DynamicOutputProcessorService;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;


@Slf4j
@AllArgsConstructor
@Service
public class ReportingServiceImpl implements ReportingService {

    private final ResourcesLoader resourcesLoader;

    private final DynamicOutputProcessorService outputProcessor;

    private final MessageSource messageSource;
    private final Translator translator;

    /**
     * to export to paths
     * @<code>
     *     exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outputPath + "/" + fileName));
     * </code>
     * we can also utilize {@link net.sf.jasperreports.engine.util.JRSaver}
     * @<code>
     *     JRSaver.saveObject(jasperReport, "employeeReport.jasper");
     * </code>
     **/

    @Override
    public void generateReport(ReportRequest reportRequest, ReportExportType accept, HttpServletResponse servletResponse)
            throws JRException, IOException, UnsupportedItemException {
        log.info("XThread: " + Thread.currentThread().getName());

        //first check for report existence
        Report report = resourcesLoader.getReportRepository().findById(reportRequest.getReportId())
                .orElseThrow(() -> new BadReportEntryException("reportId", "No Report found by the given id."));

        //then, check for locale match meaning: (a template that supports the language requested by the user).
        ReportLocale reportLocale = report.getLocales().stream()
                .filter(l -> l.getValue().equalsIgnoreCase(reportRequest.getLocale()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedItemException("Locale", reportRequest.getLocale(), report.getReportLocalesValues()));

        Map<String, Object> parametersMap = processReportRequest(report, reportRequest.getData());

        JasperPrint jasperPrint = JasperFillManager.fillReport(resourcesLoader.getJasperReports().get(reportLocale.getId())
                , parametersMap
                , new JREmptyDataSource());



        String fileName = String.format("%s%s", "test", new SimpleDateFormat("yyyyMMddhhmmss'."+accept.toString().toLowerCase()+"'")
                .format(new Date()));

        log.info("fileName: {}",fileName);

        servletResponse.setHeader("Content-Disposition", "attachment; filename=" + fileName);

        outputProcessor.export(accept,jasperPrint, servletResponse.getOutputStream());
    }

    public Map<String, Object> processReportRequest(Report report, Map<String, Object> inputMap) {

        //filled then injected to the report
        Map<String, Object> parametersMap = new HashMap<>();

        final String reportName="reportName";//to be within constants
        if (inputMap.get(reportName)!=null)//optional feature to change the default title
            parametersMap.put(reportName,inputMap.get(reportName));
        else
            parametersMap.put(reportName,report.getName());
        // first extract lists
        report.getReportTables().parallelStream().forEach(reportList -> {
            // initialize a list for each report list -->to be injected to the JasperReport
            List<Map<?, ?>> injectedList = new ArrayList<>();
            //get the list using its name defined on DB
            List<?> list = Optional.ofNullable((List<?>) inputMap.get(reportList.getName()))
                    .orElseThrow(() -> new BadReportEntryException(reportList.getName(), report));

            list.forEach(listItem ->
                    //for each map/entry on the list
                    injectedList.add(((Map<?, ?>) listItem))
            );
            //finally,add the list
            parametersMap.put(reportList.getName(), new JRBeanCollectionDataSource(injectedList));
        });

        // then extract first level fields
        report.getReportFields().parallelStream()
                .forEach(reportField ->
                        parametersMap.put(
                                reportField.getName(),
                                Optional.ofNullable(inputMap.get(reportField.getName()))//to fix if any ClassCastException: Cannot cast java.lang.Integer to java.lang.String
                                        .orElseThrow(() -> new BadReportEntryException(reportField.getName(), report))
                        )
                );

        report.getImages().parallelStream().forEach(img ->
                parametersMap.put(
                        img.getName(),
                        Optional.ofNullable(resourcesLoader.getImages().get(img.getId()))
                                .orElseThrow(() -> new BadReportEntryException(img.getName(), report))
                ));

        return parametersMap;
    }

    @Override
    public void generateReport(ObjectNode reportRequest, String language,
                               ReportExportType accept, HttpServletResponse servletResponse) throws JRException, IOException {
        log.info("XThread: " + Thread.currentThread().getName());
        Map<String, Object> parametersMap = new HashMap<>();
        ArrayNode arrayNode=new ArrayNode(JsonNodeFactory.instance);
        reportRequest.fields().forEachRemaining(entry -> {
            String name;
            try {
                name = messageSource.getMessage(entry.getKey(), null, Locale.forLanguageTag(language));
            } catch (NoSuchMessageException e) {
                if (language.equals(Language.ENGLISH.value())) {
                    name = translator.breakCamel(entry.getKey()).concat(" :");
                } else {
                    //try to translate
                    name = translator.translate(translator.breakCamel(entry.getKey()),
                            Language.ENGLISH.value(),
                            Language.ARABIC.value());
                }
            }
            ObjectNode node = new ObjectNode(JsonNodeFactory.instance);
            node.putIfAbsent("name", TextNode.valueOf(name));
            node.putIfAbsent("value", TextNode.valueOf(String.valueOf(entry.getValue())));
            arrayNode.add(node);
        });
        ByteArrayInputStream jsonDataStream = new ByteArrayInputStream(arrayNode.toString().getBytes());
        try {
            parametersMap.put("invoiceDataSource", new JsonDataSource(jsonDataStream));
        } catch (JRException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());
        }
        //todo: pass report id
        Report report = resourcesLoader.getReportRepository()
                .findById(2L)
                .orElseThrow();
        parametersMap.put("title", report.getName());
        report.getImages().forEach(img ->
                parametersMap.put(
                        img.getName(),
                        Optional.ofNullable(resourcesLoader.getImages().get(img.getId()))
                                .orElseThrow(() -> new BadReportEntryException(img.getName(), img.getName()))
                ));
        //check if locale is supported and return the ReportLocale
        ReportLocale reportLocale =
                report.getLocales().stream().filter(lo->lo.getValue().equalsIgnoreCase(language))
                        .findFirst().orElseThrow(()-> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Requested Locale is not supported."));
        JasperReport jasperReport = resourcesLoader.getJasperReports().get(reportLocale.getId());
        JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport
                , parametersMap
                , new JREmptyDataSource());
        String fileName = String.format("%s%s", "test", new SimpleDateFormat("yyyyMMddhhmmss'." + accept.toString().toLowerCase() + "'")
                .format(new Date()));
        log.info("fileName: {}", fileName);
        servletResponse.setHeader("Content-Disposition", "attachment; filename=" + fileName);
        outputProcessor.export(accept, jasperPrint, servletResponse.getOutputStream());
    }


}
