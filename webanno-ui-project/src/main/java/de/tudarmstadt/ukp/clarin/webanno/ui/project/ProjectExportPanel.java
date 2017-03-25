/*
 * Copyright 2012
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.ui.project;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.cas.CASRuntimeException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.link.DownloadLink;
import org.apache.wicket.markup.html.panel.FeedbackPanel;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.wicketstuff.progressbar.ProgressBar;
import org.wicketstuff.progressbar.Progression;
import org.wicketstuff.progressbar.ProgressionModel;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationService;
import de.tudarmstadt.ukp.clarin.webanno.api.RepositoryService;
import de.tudarmstadt.ukp.clarin.webanno.api.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.ZipUtils;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocumentState;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.ConstraintSet;
import de.tudarmstadt.ukp.clarin.webanno.model.MiraTemplate;
import de.tudarmstadt.ukp.clarin.webanno.model.Mode;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocumentState;
import de.tudarmstadt.ukp.clarin.webanno.model.Tag;
import de.tudarmstadt.ukp.clarin.webanno.model.TagSet;
import de.tudarmstadt.ukp.clarin.webanno.model.User;
import de.tudarmstadt.ukp.clarin.webanno.model.export.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.export.ProjectPermission;
import de.tudarmstadt.ukp.clarin.webanno.model.export.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.support.AJAXDownload;
import de.tudarmstadt.ukp.clarin.webanno.support.JSONUtil;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.Logging;
import de.tudarmstadt.ukp.clarin.webanno.tsv.WebannoTsv3Writer;
import de.tudarmstadt.ukp.clarin.webanno.ui.automation.service.AutomationService;

/**
 * A Panel used to add Project Guidelines in a selected {@link Project}
 *
 */
public class ProjectExportPanel extends Panel {
	private static final long serialVersionUID = 2116717853865353733L;

    private static final Logger LOG = LoggerFactory.getLogger(ProjectPage.class);
    
    private static final String FORMAT_AUTO = "AUTO";

	private static final String META_INF = "/" + ImportUtil.META_INF;
	public static final String EXPORTED_PROJECT = ImportUtil.EXPORTED_PROJECT;
	private static final String SOURCE_FOLDER = "/"+ImportUtil.SOURCE;
	private static final String CURATION_AS_SERIALISED_CAS = "/"+ImportUtil.CURATION_AS_SERIALISED_CAS+"/";
	private static final String CURATION_FOLDER = "/curation/";
	private static final String LOG_FOLDER = "/" + ImportUtil.LOG_DIR;
	private static final String GUIDELINES_FOLDER = "/"+ImportUtil.GUIDELINE;
	private static final String ANNOTATION_CAS_FOLDER = "/"+ImportUtil.ANNOTATION_AS_SERIALISED_CAS+"/";
	private static final String ANNOTATION_ORIGINAL_FOLDER = "/annotation/";
   private static final String CONSTRAINTS = "/constraints/";


	private static final String CURATION_USER = "CURATION_USER";
	private static final String CORRECTION_USER = "CORRECTION_USER";

	@SpringBean(name = "annotationService")
	private AnnotationService annotationService;

    @SpringBean(name = "automationService")
    private AutomationService automationService;

	@SpringBean(name = "documentRepository")
	private RepositoryService repository;

	@SpringBean(name = "userRepository")
	private UserDao userRepository;

	private int progress = 0;
	private ProgressBar fileGenerationProgress;
	@SuppressWarnings("unused")
	private AjaxLink<Void> exportProjectLink;

	private String fileName;
	private String downloadedFile;
	@SuppressWarnings("unused")
	private String projectName;

	private transient Thread thread = null;
	private transient FileGenerator runnable = null;

	private boolean enabled = true;
	private boolean canceled = false;

	public ProjectExportPanel(String id, final IModel<Project> aProjectModel) {
		super(id);
		add(new ProjectExportForm("exportForm", aProjectModel.getObject()));
	}

	private boolean existsCurationDocument(Project aProject) {
		boolean curationDocumentExist = false;
		List<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument> documents = repository
				.listSourceDocuments(aProject);

		for (de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument sourceDocument : documents) {

			// If the curation document is finished
			if (SourceDocumentState.CURATION_FINISHED.equals(sourceDocument.getState())) {
				curationDocumentExist = true;
				break;
			}
		}
		return curationDocumentExist;
	}
	
    /**
     * Copy, if exists, curation documents to a folder that will be exported as Zip file
     *
     * @param aProject
     *            The {@link Project}
     * @param aCurationDocumentExist
     *            Check if Curation document exists
     * @param aCopyDir
     *            The folder where curated documents are copied to be exported as Zip File
     */
    private void exportCuratedDocuments(ProjectExportModel aModel, File aCopyDir)
        throws FileNotFoundException, UIMAException, IOException, ClassNotFoundException, ProjectExportException
    {
        // Get all the source documents from the project
        List<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument> documents = repository
                .listSourceDocuments(aModel.project);

        // Determine which format to use for export.
        Class<?> writer;
        if (FORMAT_AUTO.equals(aModel.format)) {
            writer = WebannoTsv3Writer.class;
        }
        else {
            writer = repository.getWritableFormats().get(
                    repository.getWritableFormatId(aModel.format));
            if (writer == null) {
                writer = WebannoTsv3Writer.class;
            }
        }
        
        int initProgress = progress-1;
        int i = 1;
        for (de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument sourceDocument : documents) {
            File curationCasDir = new File(aCopyDir + CURATION_AS_SERIALISED_CAS
                    + sourceDocument.getName());
            FileUtils.forceMkdir(curationCasDir);

            File curationDir = new File(aCopyDir + CURATION_FOLDER + sourceDocument.getName());
            FileUtils.forceMkdir(curationDir);

            // If the curation document is finished
            if (SourceDocumentState.CURATION_FINISHED.equals(sourceDocument.getState())) {
                File curationCasFile = repository.getCasFile(sourceDocument, CURATION_USER);
                if (curationCasFile.exists()) {
                    // Copy CAS - this is used when importing the project again
                    FileUtils.copyFileToDirectory(curationCasFile, curationCasDir);
                    
                    // Copy secondary export format for convenience - not used during import
                    try {
						File curationFile = repository.exportAnnotationDocument(sourceDocument,
						        CURATION_USER, writer, CURATION_USER, Mode.CURATION);
						FileUtils.copyFileToDirectory(curationFile, curationDir);
						FileUtils.forceDelete(curationFile);
					} catch (Exception e) {
						error("Unexpected error while exporting project: " + ExceptionUtils.getRootCauseMessage(e) );
						throw new ProjectExportException("Aborting due to unrecoverable error while exporting!");
					}
                }
            }
            
            progress = initProgress+ (int) Math.ceil(((double) i)/documents.size()*10.0);
            i++;
        }
    }
    
    public class ProjectExportForm
        extends Form<ProjectExportModel>
    {
        private static final long serialVersionUID = 9151007311548196811L;

        public ProjectExportForm(String id, Project aProject)
        {
            super(id, new CompoundPropertyModel<ProjectExportModel>(
                    new ProjectExportModel(aProject)));
            
            add(new DropDownChoice<String>("format", new LoadableDetachableModel<List<String>>()
            {
                private static final long serialVersionUID = 1L;

                @Override
                protected List<String> load()
                {                    
                    List<String> formats = new ArrayList<String>(
                            repository.getWritableFormatLabels());
                    formats.add(0, FORMAT_AUTO);
                    return formats;
                }
            }) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean wantOnSelectionChangedNotifications()
                {
                    // Needed to update the model with the selection because the DownloadLink does
                    // not trigger a form submit.
                    return true;
                }
            });
            
            add(new DownloadLink("export", new LoadableDetachableModel<File>() {
                private static final long serialVersionUID = 840863954694163375L;

                @Override
                protected File load() {
                    File exportFile = null;
                    File exportTempDir = null;
                    try {
                        exportTempDir = File.createTempFile("webanno", "export");
                        exportTempDir.delete();
                        exportTempDir.mkdirs();

                        boolean curationDocumentExist = existsCurationDocument(ProjectExportForm.this
                                .getModelObject().project);

                        if (!curationDocumentExist) {
                            error("No curation document created yet for this document");
                        } else {
                            exportCuratedDocuments(ProjectExportForm.this.getModelObject(),
                                    exportTempDir);
                            ZipUtils.zipFolder(exportTempDir, new File(
                                    exportTempDir.getAbsolutePath() + ".zip"));
                            exportFile = new File(exportTempDir.getAbsolutePath()
                                    + ".zip");

                        }
                    } catch (CASRuntimeException e) {
                    	cancelOperationOnError();
                        error(e.getMessage());
                    } 
                    catch (Exception e){
                    	error(e.getMessage());
                    	cancelOperationOnError();
                    	
                    }
                    finally {
                        try {
                            FileUtils.forceDelete(exportTempDir);
                        } catch (IOException e) {
                            error("Unable to delete temp file");
                        }
                    }

                    return exportFile;
                }

				private void cancelOperationOnError() {
					if (thread != null) {
                        progress = 100;
                        thread.interrupt();
                    }
					
				}
            },new LoadableDetachableModel<String>(){
                private static final long serialVersionUID = 2591915908792854707L;
//                Provide meaningful name to curated documents zip
                @Override
                protected String load()
                {
                    StringBuffer _fileName = new StringBuffer();
                    _fileName.append(ProjectExportForm.this.getModelObject().project.getName());
                    _fileName.append("_curated_documents_");
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd_HHmm");
                    _fileName.append(fmt.format(new Date()));
                    _fileName.append(".zip");
                    return _fileName.toString();
                }
            }) {
                private static final long serialVersionUID = 5630612543039605914L;

                @Override
                public boolean isVisible() {
                    return existsCurationDocument(ProjectExportForm.this
                            .getModelObject().project);
                }

                @Override
                public boolean isEnabled() {
                    return enabled;

                }
                
                @Override
                public void onClick()
                {
                    try {
                        super.onClick();
                    }
                    catch (IllegalStateException e) {
                        LOG.error("Error: {}", e.getMessage(), e);
                        error("Unable to export curated documents because of exception while processing.");
                    }
                }
            }.setDeleteAfterDownload(true)).setOutputMarkupId(true);

            final AJAXDownload exportProject = new AJAXDownload() {
                @Override
                protected String getFileName() {
                    String name;
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd_HHmm");
                    try {
                        name = URLEncoder.encode(
                                ProjectExportForm.this.getModelObject().project.getName(), "UTF-8");
                    }
                    catch (UnsupportedEncodingException e) {
                        name = super.getFileName();
                    }
                    
                    name = FilenameUtils.removeExtension(name);
                    name += "_" + fmt.format(new Date()) + ".zip";
                    
                    return name;
                };
            };

            fileGenerationProgress = new ProgressBar("progress", new ProgressionModel()
            {
                private static final long serialVersionUID = 1971929040248482474L;

                @Override
                protected Progression getProgression()
                {
                    return new Progression(progress);
                }
            })
            {
                private static final long serialVersionUID = -6599620911784164177L;

                @Override
                protected void onFinished(AjaxRequestTarget target)
                {
                    if (!canceled && !fileName.equals(downloadedFile)) {
                        exportProject.initiate(target, fileName);
                        downloadedFile = fileName;
                        
                        while (!runnable.getMessages().isEmpty()) {
                            info(runnable.getMessages().poll());
                        }

                        enabled = true;
                        ProjectPage.visible = true;
                        target.add(ProjectPage.projectSelectionForm.setEnabled(true));
                        target.add(ProjectPage.projectDetailForm);
                        target.addChildren(getPage(), FeedbackPanel.class);
                        info("Project export complete");
                    }
                    else if (canceled) {
                        enabled = true;
                        ProjectPage.visible = true;
                        target.add(ProjectPage.projectSelectionForm.setEnabled(true));
                        target.add(ProjectPage.projectDetailForm);
                        target.addChildren(getPage(), FeedbackPanel.class);
                        info("Project export cancelled");
                    }
                }
            };

            fileGenerationProgress.add(exportProject);
            add(fileGenerationProgress);

            add(exportProjectLink = new AjaxLink<Void>("exportProject") {
                private static final long serialVersionUID = -5758406309688341664L;

                @Override
                public boolean isEnabled() {
                    return enabled;
                }

                @Override
                public void onClick(final AjaxRequestTarget target) {
                    enabled = false;
                    canceled = true;
                    progress = 0;
                    ProjectPage.projectSelectionForm.setEnabled(false);
                    ProjectPage.visible = false;
                    target.add(ProjectExportPanel.this.getPage());
                    fileGenerationProgress.start(target);
                    Authentication authentication = SecurityContextHolder.getContext()
                            .getAuthentication();
                    runnable = new FileGenerator(ProjectExportForm.this.getModelObject(), target,
                            authentication.getName());
                    thread = new Thread(runnable);
                    thread.start();
                }
            });

            add(new AjaxLink<Void>("cancel") {
                private static final long serialVersionUID = 5856284172060991446L;

                @Override
                public void onClick(final AjaxRequestTarget target) {
                    if (thread != null) {
                        progress = 100;
                        thread.interrupt();
                    }
                }

                /* (non-Javadoc)
                 * @see org.apache.wicket.Component#isEnabled()
                 */
                @Override
                public boolean isEnabled()
                {
//                    Enabled only if the export button has been disabled (during export)
                    return (!enabled) ;
                }
            });
        }
    }
    
    public static class ProjectExportModel
        implements Serializable
    {
        private static final long serialVersionUID = -4486934192675904995L;
        
        String format;
        Project project;
        
        public ProjectExportModel(Project aProject)
        {
            format = FORMAT_AUTO;
            project = aProject;
        }
    }

    public class FileGenerator
        implements Runnable
    {
        private String username;
        private ProjectExportModel model;
        private AjaxRequestTarget target;
        private Queue<String> messages = new ConcurrentLinkedQueue<>();

        public FileGenerator(ProjectExportModel aModel, AjaxRequestTarget aTarget, String aUsername)
        {
            model = aModel;
            target = aTarget;
            username = aUsername;
        }

        @Override
        public void run()
        {
            // We are in a new thread. Set up thread-specific MDC
            MDC.put(Logging.KEY_USERNAME, username);
            MDC.put(Logging.KEY_PROJECT_ID, String.valueOf(model.project.getId()));
            MDC.put(Logging.KEY_REPOSITORY_PATH, repository.getDir().toString());
            
            File file;
            try {
                Thread.sleep(100); // Why do we sleep here?
                file = generateZipFile(model, target);
                fileName = file.getAbsolutePath();
                projectName = model.project.getName();
                canceled = false;
            }
            catch (FileNotFoundException e) {
                LOG.error("Unable to find some project file(s) during project export", e);
                messages.add("Unable to find file during project export: " + ExceptionUtils.getRootCauseMessage(e));
            }
            catch (Throwable e){
            	LOG.error("Unexpected error during project export", e);
                messages.add("Unexpected error during project export: "
                        + ExceptionUtils.getRootCauseMessage(e));
                if(thread!=null){
                	canceled = true;
                	progress = 100; 
                    thread.interrupt();
                }
                
            }
        }

        public Queue<String> getMessages()
        {
            return messages;
        }

        public File generateZipFile(final ProjectExportModel aModel, AjaxRequestTarget target)
            throws IOException, UIMAException, ClassNotFoundException, ZippingException,
            InterruptedException, ProjectExportException
        {
            File exportTempDir = null;
            // all metadata and project settings data from the database as JSON file
            File projectSettings = null;
            projectSettings = File.createTempFile(EXPORTED_PROJECT, ".json");
            // Directory to store source documents and annotation documents
            exportTempDir = File.createTempFile("webanno-project", "export");
            exportTempDir.delete();
            exportTempDir.mkdirs();

            File projectZipFile = new File(exportTempDir.getAbsolutePath() + ".zip");
            if (aModel.project.getId() == 0) {
                throw new ProjectExportException(
                        "Project not yet created. Please save project details first!");
            }

            exportProjectSettings(aModel.project, projectSettings, exportTempDir);
            progress = 9;
            exportSourceDocuments(aModel.project, exportTempDir);
            exportAnnotationDocuments(aModel, exportTempDir);
            exportProjectLog(aModel.project, exportTempDir);
            exportGuideLine(aModel.project, exportTempDir);
            exportProjectMetaInf(aModel.project, exportTempDir);
            exportProjectConstraints(aModel.project, exportTempDir);
            progress = 90;
            try {
				exportCuratedDocuments(aModel, exportTempDir);
			} catch (ProjectExportException e) {
				//cancel export operation here
			    error(e.getMessage());
				if (thread != null) {
                    progress = 100;
                    thread.interrupt();
                }
			}
            try {
                ZipUtils.zipFolder(exportTempDir, projectZipFile);
            }
            catch (Exception e) {
                throw new ZippingException("Unable to Zip the file");
            }
            finally {
                FileUtils.forceDelete(projectSettings);
                System.gc();
                FileUtils.forceDelete(exportTempDir);
            }
            progress = 100;

            return projectZipFile;
        }

        private void exportProjectSettings(Project aProject, File aProjectSettings,
                File aExportTempDir)
        {
            de.tudarmstadt.ukp.clarin.webanno.model.export.Project exProjekt = new de.tudarmstadt.ukp.clarin.webanno.model.export.Project();
            exProjekt.setDescription(aProject.getDescription());
            exProjekt.setName(aProject.getName());
            exProjekt.setMode(aProject.getMode());
            exProjekt.setScriptDirection(aProject.getScriptDirection());
            exProjekt.setVersion(aProject.getVersion());
            exProjekt.setDisableExport(aProject.isDisableExport());

            List<de.tudarmstadt.ukp.clarin.webanno.model.export.AnnotationLayer> exLayers = new ArrayList<>();
            // Store map of layer and its equivalent exLayer so that the attach type
            // is attached later
            Map<AnnotationLayer, de.tudarmstadt.ukp.clarin.webanno.model.export.AnnotationLayer> layerToExLayers = new HashMap<>();
            // Store map of feature and its equivalent exFeature so that the attach
            // feature is attached
            // later
            Map<AnnotationFeature, de.tudarmstadt.ukp.clarin.webanno.model.export.AnnotationFeature> featureToExFeatures = new HashMap<>();
            for (AnnotationLayer layer : annotationService.listAnnotationLayer(aProject)) {
                exLayers.add(ImportUtil.exportLayerDetails(layerToExLayers, featureToExFeatures,
                        layer, annotationService));
            }

            // add the attach type and attache feature to the exported layer and
            // exported feature
            for (AnnotationLayer layer : layerToExLayers.keySet()) {
                if (layer.getAttachType() != null) {
                    layerToExLayers.get(layer).setAttachType(
                            layerToExLayers.get(layer.getAttachType()));
                }
                if (layer.getAttachFeature() != null) {
                    layerToExLayers.get(layer).setAttachFeature(
                            featureToExFeatures.get(layer.getAttachFeature()));
                }
            }
            exProjekt.setLayers(exLayers);

            List<de.tudarmstadt.ukp.clarin.webanno.model.export.TagSet> extTagSets = new ArrayList<>();
            for (TagSet tagSet : annotationService.listTagSets(aProject)) {
                de.tudarmstadt.ukp.clarin.webanno.model.export.TagSet exTagSet = new de.tudarmstadt.ukp.clarin.webanno.model.export.TagSet();
                exTagSet.setCreateTag(tagSet.isCreateTag());
                exTagSet.setDescription(tagSet.getDescription());
                exTagSet.setLanguage(tagSet.getLanguage());
                exTagSet.setName(tagSet.getName());
                List<de.tudarmstadt.ukp.clarin.webanno.model.export.Tag> exTags = new ArrayList<>();
                for (Tag tag : annotationService.listTags(tagSet)) {
                    de.tudarmstadt.ukp.clarin.webanno.model.export.Tag exTag = new de.tudarmstadt.ukp.clarin.webanno.model.export.Tag();
                    exTag.setDescription(tag.getDescription());
                    exTag.setName(tag.getName());
                    exTags.add(exTag);
                }
                exTagSet.setTags(exTags);
                extTagSets.add(exTagSet);
            }

            exProjekt.setTagSets(extTagSets);
            List<SourceDocument> sourceDocuments = new ArrayList<SourceDocument>();
            List<AnnotationDocument> annotationDocuments = new ArrayList<AnnotationDocument>();

            // Store map of source document and exSourceDocument
            Map<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument, SourceDocument> exDocuments = new HashMap<>();
            // add source documents to a project
            List<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument> documents = repository
                    .listSourceDocuments(aProject);
            documents.addAll(automationService.listTabSepDocuments(aProject));
            for (de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument sourceDocument : documents) {

                SourceDocument exDocument = new SourceDocument();
                exDocument.setFormat(sourceDocument.getFormat());
                exDocument.setName(sourceDocument.getName());
                exDocument.setState(sourceDocument.getState());
                exDocument.setProcessed(sourceDocument.isProcessed());
                exDocument.setTimestamp(sourceDocument.getTimestamp());
                exDocument.setTrainingDocument(sourceDocument.isTrainingDocument());
                exDocument.setSentenceAccessed(sourceDocument.getSentenceAccessed());
                exDocument.setProcessed(false);

                if (sourceDocument.getFeature() != null) {
                    exDocument.setFeature(featureToExFeatures.get(sourceDocument.getFeature()));
                }

                // add annotation document to Project
                for (de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument annotationDocument : repository
                        .listAnnotationDocuments(sourceDocument)) {
                    AnnotationDocument annotationDocumentToExport = new AnnotationDocument();
                    annotationDocumentToExport.setName(annotationDocument.getName());
                    annotationDocumentToExport.setState(annotationDocument.getState());
                    annotationDocumentToExport.setUser(annotationDocument.getUser());
                    annotationDocumentToExport.setTimestamp(annotationDocument.getTimestamp());
                    annotationDocumentToExport.setSentenceAccessed(annotationDocument.getSentenceAccessed());
                    annotationDocuments.add(annotationDocumentToExport);
                }
                sourceDocuments.add(exDocument);
                exDocuments.put(sourceDocument, exDocument);
            }

            exProjekt.setSourceDocuments(sourceDocuments);
            exProjekt.setAnnotationDocuments(annotationDocuments);

            List<ProjectPermission> projectPermissions = new ArrayList<ProjectPermission>();

            // add project permissions to the project
            for (User user : repository.listProjectUsersWithPermissions(aProject)) {
                for (de.tudarmstadt.ukp.clarin.webanno.model.ProjectPermission permission : repository
                        .listProjectPermissionLevel(user, aProject)) {
                    ProjectPermission permissionToExport = new ProjectPermission();
                    permissionToExport.setLevel(permission.getLevel());
                    permissionToExport.setUser(user.getUsername());
                    projectPermissions.add(permissionToExport);
                }
            }

            exProjekt.setProjectPermissions(projectPermissions);

            // export automation Mira template
            List<de.tudarmstadt.ukp.clarin.webanno.model.export.MiraTemplate> exTemplates = new ArrayList<>();
            for (MiraTemplate template : automationService.listMiraTemplates(aProject)) {
                de.tudarmstadt.ukp.clarin.webanno.model.export.MiraTemplate exTemplate = new de.tudarmstadt.ukp.clarin.webanno.model.export.MiraTemplate();
                exTemplate.setAnnotateAndPredict(template.isAnnotateAndRepeat());
                exTemplate.setAutomationStarted(template.isAutomationStarted());
                exTemplate.setCurrentLayer(template.isCurrentLayer());
                exTemplate.setResult(template.getResult());
                exTemplate.setTrainFeature(featureToExFeatures.get(template.getTrainFeature()));

                if (template.getOtherFeatures().size() > 0) {
                    Set<de.tudarmstadt.ukp.clarin.webanno.model.export.AnnotationFeature> exOtherFeatures = new HashSet<>();
                    for (AnnotationFeature feature : template.getOtherFeatures()) {
                        exOtherFeatures.add(featureToExFeatures.get(feature));
                    }
                    exTemplate.setOtherFeatures(exOtherFeatures);
                }
                exTemplates.add(exTemplate);
            }

            exProjekt.setMiraTemplates(exTemplates);

            try {
                JSONUtil.generatePrettyJson(exProjekt, aProjectSettings);
                FileUtils.copyFileToDirectory(aProjectSettings, aExportTempDir);
            }
            catch (IOException e) {
                error("File Path not found or No permision to save the file!");
            }
        }

        /**
         * Copy source documents from the file system of this project to the export folder
         */
        private void exportSourceDocuments(Project aProject, File aCopyDir)
            throws IOException, ProjectExportException
        {
            File sourceDocumentDir = new File(aCopyDir + SOURCE_FOLDER);
            FileUtils.forceMkdir(sourceDocumentDir);
            // Get all the source documents from the project
            List<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument> documents = repository
                    .listSourceDocuments(aProject);
            documents.addAll(automationService.listTabSepDocuments(aProject));
            int i = 1;
            for (de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument sourceDocument : documents) {
                try {
					FileUtils.copyFileToDirectory(repository.getSourceDocumentFile(sourceDocument),
					        sourceDocumentDir);
					progress = (int) Math.ceil(((double) i) / documents.size() * 10.0);
					i++;
				} catch (FileNotFoundException e) {
//					error(e.getMessage());
				    StringBuffer errorMessage = new StringBuffer();
				    errorMessage.append("Source file '");
				    errorMessage.append(sourceDocument.getName());
				    errorMessage.append("' related to project couldn't be located in repository");
				    LOG.error(errorMessage.toString(), ExceptionUtils.getRootCause(e));
					messages.add(errorMessage.toString());
					throw new ProjectExportException("Couldn't find some source file(s) related to project");
//					continue;
					
				}
            }
        }

        /**
         * Copy annotation document as Serialized CAS from the file system of this project to the
         * export folder
         *
         * @throws ClassNotFoundException
         * @throws UIMAException
         */
        private void exportAnnotationDocuments(ProjectExportModel aModel, File aCopyDir)
            throws IOException, UIMAException, ClassNotFoundException
        {
            List<de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument> documents = repository
                    .listSourceDocuments(aModel.project);
            int i = 1;
            int initProgress = progress;
            for (de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument sourceDocument : documents) {
                // Determine which format to use for export
                String formatId;
                if (FORMAT_AUTO.equals(aModel.format)) {
                    formatId = sourceDocument.getFormat();
                }
                else {
                    formatId = repository.getWritableFormatId(aModel.format);
                }
                Class<?> writer = repository.getWritableFormats().get(formatId);
                if (writer == null) {
                    String msg = "[" + sourceDocument.getName()
                            + "] No writer found for format [" + formatId
                            + "] - exporting as WebAnno TSV instead.";
                    // Avoid repeating the same message over for different users
                    if (!messages.contains(msg)) {
                        messages.add(msg);
                    }
                    writer = WebannoTsv3Writer.class;
                }

                // Export annotations from regular users
                for (de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument annotationDocument : repository
                        .listAnnotationDocuments(sourceDocument)) {
                    // copy annotation document only for ACTIVE users and the state of the 
                    // annotation document is not NEW/IGNORE
                    if (userRepository.get(annotationDocument.getUser()) != null
                            && !annotationDocument.getState().equals(AnnotationDocumentState.NEW)
                            && !annotationDocument.getState()
                                    .equals(AnnotationDocumentState.IGNORE)) {
                        File annotationDocumentAsSerialisedCasDir = new File(
                                aCopyDir.getAbsolutePath() + ANNOTATION_CAS_FOLDER
                                        + sourceDocument.getName());
                        File annotationDocumentDir = new File(aCopyDir.getAbsolutePath()
                                + ANNOTATION_ORIGINAL_FOLDER + sourceDocument.getName());

                        FileUtils.forceMkdir(annotationDocumentAsSerialisedCasDir);
                        FileUtils.forceMkdir(annotationDocumentDir);

                        File annotationFileAsSerialisedCas = repository.getCasFile(
                                sourceDocument, annotationDocument.getUser());

                        File annotationFile = null;
                        if (annotationFileAsSerialisedCas.exists() && writer != null) {
                            annotationFile = repository.exportAnnotationDocument(sourceDocument,
                                    annotationDocument.getUser(), writer,
                                    annotationDocument.getUser(), Mode.ANNOTATION, false);
                        }
                        if (annotationFileAsSerialisedCas.exists()) {
                            FileUtils.copyFileToDirectory(annotationFileAsSerialisedCas,
                                    annotationDocumentAsSerialisedCasDir);
                            if (writer != null) {
                                FileUtils
                                        .copyFileToDirectory(annotationFile, annotationDocumentDir);
                                FileUtils.forceDelete(annotationFile);
                            }
                        }
                    }
                }
                
                // BEGIN FIXME #1224 CURATION_USER and CORRECTION_USER files should be exported in annotation_ser
                // If this project is a correction project, add the auto-annotated  CAS to same 
                // folder as CURATION_FOLDER
                if (aModel.project.getMode().equals(Mode.AUTOMATION)
                        || aModel.project.getMode().equals(Mode.CORRECTION)) {
                    File correctionCasFile = repository.getCasFile(sourceDocument,
                            CORRECTION_USER);
                    if (correctionCasFile.exists()) {
                        // Copy CAS - this is used when importing the project again
                        File curationCasDir = new File(aCopyDir + CURATION_AS_SERIALISED_CAS
                                + sourceDocument.getName());
                        FileUtils.forceMkdir(curationCasDir);
                        FileUtils.copyFileToDirectory(correctionCasFile, curationCasDir);
                        
                        // Copy secondary export format for convenience - not used during import
                        File curationDir = new File(aCopyDir + CURATION_FOLDER + sourceDocument.getName());
                        FileUtils.forceMkdir(curationDir);
                        File correctionFile = repository.exportAnnotationDocument(sourceDocument,
                                CORRECTION_USER, writer, CORRECTION_USER, Mode.CORRECTION);
                        FileUtils.copyFileToDirectory(correctionFile, curationDir);
                        FileUtils.forceDelete(correctionFile);
                    }
                }
                // END FIXME #1224 CURATION_USER and CORRECTION_USER files should be exported in annotation_ser
                
                progress = initProgress + (int) Math.ceil(((double) i) / documents.size() * 80.0);
                i++;
            }
        }

        /**
         * Copy Project logs from the file system of this project to the export folder
         */
        private void exportProjectLog(Project aProject, File aCopyDir)
            throws IOException
        {
            File logDir = new File(aCopyDir + LOG_FOLDER);
            FileUtils.forceMkdir(logDir);
            if (repository.getProjectLogFile(aProject).exists()) {
                FileUtils.copyFileToDirectory(repository.getProjectLogFile(aProject), logDir);
            }
        }

        /**
         * Copy Project guidelines from the file system of this project to the export folder
         */
        private void exportGuideLine(Project aProject, File aCopyDir)
            throws IOException
        {
            File guidelineDir = new File(aCopyDir + GUIDELINES_FOLDER);
            FileUtils.forceMkdir(guidelineDir);
            File annotationGuidlines = repository.getGuidelinesFile(aProject);
            if (annotationGuidlines.exists()) {
                for (File annotationGuideline : annotationGuidlines.listFiles()) {
                    FileUtils.copyFileToDirectory(annotationGuideline, guidelineDir);
                }
            }
        }

        /**
         * Copy Project guidelines from the file system of this project to the export folder
         */
        private void exportProjectMetaInf(Project aProject, File aCopyDir)
            throws IOException
        {
            File metaInfDir = new File(aCopyDir + META_INF);
            FileUtils.forceMkdir(metaInfDir);
            File metaInf = repository.getMetaInfFolder(aProject);
            if (metaInf.exists()) {
                FileUtils.copyDirectory(metaInf, metaInfDir);
            }
        }
        
        /**
         * Copy Project Constraints from file system of this project to export folder
         */
        private void exportProjectConstraints(Project project, File exportTempDir)
            throws IOException
        {
            File constraintsDir = new File(exportTempDir + CONSTRAINTS);
            FileUtils.forceMkdir(constraintsDir);
            String fileName;
            for (ConstraintSet set : repository.listConstraintSets(project)) {
                fileName = set.getName();
             /*
              * Copying with file's original name to save ConstraintSet's name
              */
                FileUtils.copyFile(repository.exportConstraintAsFile(set),
                        new File(constraintsDir, fileName));
            }

        }
    }
}
