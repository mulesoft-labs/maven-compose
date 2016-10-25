package org.mule.maven.composite;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.inheritance.InheritanceAssembler;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;

@Component(role = ModelBuilder.class, hint = "default")
public class CompositeModelBuilder implements ModelBuilder {

	private static final String PROPERTY_PREFIX = "maven.compose.";

	@Requirement
	private Logger logger;

	@Requirement
	private PlexusContainer plexus;

	@Requirement
	private InheritanceAssembler assembler;

	@Requirement
	private RepositorySystem repositorySystem;

	@Requirement
	private ResolutionErrorHandler resolutionErrorHandler;

	private ModelBuilder decoratedModelBuilder;

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request) throws ModelBuildingException {
		ModelBuildingResult result = getModelBuilder().build(request);
		Model effectiveModel = result.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, compose(effectiveModel, request));
	}

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request, ModelBuildingResult result) throws ModelBuildingException {
		ModelBuildingResult newResult = getModelBuilder().build(request, result);
		Model effectiveModel = newResult.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, compose(effectiveModel, request));
	}

	@Override
	public Result<? extends Model> buildRawModel(File pomFile, int validationLevel, boolean locationTracking) {
		return getModelBuilder().buildRawModel(pomFile, validationLevel, locationTracking);
	}

	private ModelBuilder getModelBuilder() {
		if (decoratedModelBuilder == null) {
			try {
				List<Object> components = plexus.lookupList(ModelBuilder.class.getName());
				logger.debug(ModelBuilder.class.getName() + " available components: " + components);
				decoratedModelBuilder = (ModelBuilder) components.stream().filter(x -> x != this).findFirst()
						.orElseThrow(() -> new IllegalStateException("There should be another implementation of ModelBuilder"));
				logger.debug("Using " + ModelBuilder.class.getName() + " implementation as default: " + decoratedModelBuilder);
			} catch (ComponentLookupException e) {
				throw new RuntimeException("Error retrieving default ModelBuilder component", e);
			}
		}
		return decoratedModelBuilder;
	}

	private Model compose(Model model, ModelBuildingRequest request) {
		model.getProperties().entrySet().stream() //
				.filter(entry -> ((String) entry.getKey()).startsWith(PROPERTY_PREFIX)) //
				.map(entry -> getArtifact((String) entry.getValue())) //
				.forEach(artifact -> mergeModel(artifact, model, request));
		return model;
	}

	private void mergeModel(Artifact artifact, Model model, ModelBuildingRequest buildRequest) {
		logger.debug("Resolving composite artifact: " + artifact);
		ArtifactResolutionRequest request = getRequest(artifact);
		ArtifactResolutionResult result = repositorySystem.resolve(request);
		if (result.isSuccess()) {
			result.getArtifacts().stream() //
					.map(art -> getRequest(art.getFile(), buildRequest)) //
					.map(this::doBuild) //
					.forEach(builtModel -> assembleModel(model, builtModel));
		} else {
			rethrowErrors(request, result);
		}
	}

	private void rethrowErrors(ArtifactResolutionRequest request, ArtifactResolutionResult result) {
		try {
			resolutionErrorHandler.throwErrors(request, result);
		} catch (ArtifactResolutionException e) {
			throw new RuntimeException(e);
		}
	}

	private Model doBuild(ModelBuildingRequest request) {
		try {
			return build(request).getEffectiveModel();
		} catch (ModelBuildingException e) {
			throw new RuntimeException(e);
		}
	}

	private ModelBuildingRequest getRequest(File pomFile, ModelBuildingRequest request) {
		return new DefaultModelBuildingRequest(request).setModelSource(null).setPomFile(pomFile);
	}

	private void assembleModel(Model model, Model builtModel) {
		logger.debug("Assembling inheritance from artifact " + builtModel + " onto " + model);

		SimpleProblemCollector problems = new SimpleProblemCollector();
		assembler.assembleModelInheritance(model, builtModel, null, problems);

		problems.getWarnings().forEach(warning -> logger.warn(warning));

		List<String> allProblems = new LinkedList<>(problems.getErrors());
		allProblems.addAll(problems.getFatals());
		if (!allProblems.isEmpty()) {
			String errors = allProblems.stream().reduce("", (a, b) -> a + System.lineSeparator() + b);
			String message = "There were problems assembling the inheritance model for composite artifact " + getGav(builtModel) + ". Errors: " + errors;
			throw new RuntimeException(message);
		}
	}

	private String getGav(Model readModel) {
		return readModel.getGroupId() + readModel.getArtifactId() + readModel.getVersion();
	}

	private ArtifactResolutionRequest getRequest(Artifact artifact) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest();
		request.setArtifact(artifact);
		return request;
	}

	private DefaultArtifact getArtifact(String dependency) {
		String groupId;
		String artifactId;
		String version;
		String type = "pom";
		String scope = "compile";
		String classifier = "";
		String[] coordinates = dependency.split(":");
		if (coordinates.length < 3) {
			throw new IllegalArgumentException("Invalid dependency: " + dependency);
		} else {
			groupId = coordinates[0];
			artifactId = coordinates[1];
			version = coordinates[2];
			if (coordinates.length > 3) {
				type = coordinates[3];
			}
			if (coordinates.length > 4) {
				classifier = coordinates[4];
			}
		}
		return new DefaultArtifact(groupId, artifactId, version, scope, type, classifier, new DefaultArtifactHandler(type));
	}
}
