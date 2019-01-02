/*
 * Copyright 2016 CIRDLES
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cirdles.squid.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.bind.JAXBException;
import org.apache.commons.io.FilenameUtils;
import org.cirdles.commons.util.ResourceExtractor;
import static org.cirdles.squid.Squid.DEFAULT_SQUID3_REPORTS_FOLDER;
import static org.cirdles.squid.constants.Squid3Constants.DEFAULT_PRAWNFILE_NAME;
import org.cirdles.squid.core.CalamariReportsEngine;
import org.cirdles.squid.core.PrawnFileHandler;
import org.cirdles.squid.exceptions.SquidException;
import org.cirdles.squid.parameters.parameterModels.commonPbModels.CommonPbModel;
import org.cirdles.squid.parameters.parameterModels.physicalConstantsModels.PhysicalConstantsModel;
import org.cirdles.squid.prawn.PrawnFile;
import org.cirdles.squid.projects.SquidProject;
import org.cirdles.squid.shrimp.ShrimpFraction;
import org.cirdles.squid.tasks.TaskInterface;
import org.cirdles.squid.utilities.FileUtilities;
import org.cirdles.squid.utilities.fileUtilities.CalamariFileUtilities;
import static org.cirdles.squid.web.ZipUtility.extractZippedFile;
import org.xml.sax.SAXException;

/**
 * Created by johnzeringue on 7/27/16. Adapted by James Bowring Dec 2018.
 */
public class SquidReportingService {

    private PrawnFileHandler prawnFileHandler;
    private CalamariReportsEngine reportsEngine;

    public SquidReportingService() {
    }

    public Path generateReports(
            String myFileName,
            InputStream prawnFile,
            InputStream taskFile,
            boolean useSBM,
            boolean userLinFits,
            String refMatFilter,
            String concRefMatFilter)
            throws IOException, JAXBException, SAXException {

        // detect if prawnfile is zipped
        boolean prawnIsZip = false;
        String fileName = "";
        if (myFileName == null) {
            fileName = DEFAULT_PRAWNFILE_NAME;
        } else if (myFileName.toLowerCase().endsWith(".zip")) {            
            fileName = FilenameUtils.removeExtension(fileName);
            prawnIsZip = true;
        } else {
            fileName = myFileName;
        }

        SquidProject squidProject = new SquidProject();
        prawnFileHandler = squidProject.getPrawnFileHandler();

        CalamariFileUtilities.initSampleParametersModels();

        Path reportsZip = null;
        try {
            Path uploadDirectory = Files.createTempDirectory("upload");
            Path uploadDirectory2 = Files.createTempDirectory("upload2");

            Path prawnFilePath;
            Path taskFilePath;
            if (prawnIsZip) {
                Path prawnFilePathZip = uploadDirectory.resolve("prawn-file.zip");
                Files.copy(prawnFile, prawnFilePathZip);
                prawnFilePath = extractZippedFile(prawnFilePathZip.toFile(), uploadDirectory.toFile());
            } else {
                prawnFilePath = uploadDirectory.resolve("prawn-file.xml");
                Files.copy(prawnFile, prawnFilePath);
            }
            
            taskFilePath = uploadDirectory2.resolve("task-file.xls");
            Files.copy(taskFile, taskFilePath);

            PrawnFile prawnFileData = prawnFileHandler.unmarshallPrawnFileXML(prawnFilePath.toString(), true);
            squidProject.setPrawnFile(prawnFileData);

            // hard-wired for now
            squidProject.getTask().setCommonPbModel(CommonPbModel.getDefaultModel("GA Common Lead 2018", "1.0"));
            squidProject.getTask().setPhysicalConstantsModel(PhysicalConstantsModel.getDefaultModel("GA Physical Constants Model Squid 2", "1.0"));
            File squidTaskFile = taskFilePath.toFile();

            squidProject.createTaskFromImportedSquid25Task(squidTaskFile);

            squidProject.setDelimiterForUnknownNames("-");

            TaskInterface task = squidProject.getTask();
            task.setFilterForRefMatSpotNames(refMatFilter);
            task.setFilterForConcRefMatSpotNames(concRefMatFilter);
            task.applyTaskIsotopeLabelsToMassStations();

            Path calamarirReportsFolderAliasParent = Files.createTempDirectory("reports-destination");
            Path calamarirReportsFolderAlias = calamarirReportsFolderAliasParent.resolve(DEFAULT_SQUID3_REPORTS_FOLDER.getName() + "-from Web Service");
            File reportsDestinationFile = calamarirReportsFolderAlias.toFile();
            reportsEngine = prawnFileHandler.getReportsEngine();
            // this gives reportengine the name of the Prawnfile for use in report names
            prawnFileHandler.initReportsEngineWithCurrentPrawnFileName(fileName);

            reportsEngine.setFolderToWriteCalamariReports(reportsDestinationFile);

            try {
                reportsEngine.produceReports(
                        task.getShrimpFractions(),
                        (ShrimpFraction) task.getUnknownSpots().get(0),
                        task.getReferenceMaterialSpots().size() > 0
                        ? (ShrimpFraction) task.getReferenceMaterialSpots().get(0) : (ShrimpFraction) task.getUnknownSpots().get(0),
                        true, false);
            } catch (IOException iOException) {
            }

            squidProject.produceUnknownsCSV(true);
            squidProject.produceReferenceMaterialCSV(true);
            // next line report will not show groupings
            squidProject.produceUnknownsBySampleForETReduxCSV(true);

            Files.delete(prawnFilePath);

            Path reportsFolder = Paths.get(reportsEngine.getFolderToWriteCalamariReports().getParentFile().getPath());

            reportsZip = ZipUtility.recursivelyZip(reportsFolder);
            FileUtilities.recursiveDelete(reportsFolder);

        } catch (IOException | JAXBException | SAXException | SquidException iOException) {
        }

        return reportsZip;
    }
}