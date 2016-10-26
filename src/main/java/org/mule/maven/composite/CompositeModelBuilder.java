package org.mule.maven.composite;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.inheritance.InheritanceAssembler;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;

/**
 * {@link ModelBuilder} which takes artifacts described in properties that
 * follow a certain convention and merges them into the project's model,
 * emulating "multiple inheritance".
 *
 */
@Component(role = ModelBuilder.class, hint = "default")
public class CompositeModelBuilder implements ModelBuilder {

	private static final String PROPERTY_PREFIX = "maven-compose.";

	@Requirement
	private Logger logger;

	@Requirement
	private PlexusContainer plexus;

	@Requirement
	private ModelProcessor modelProcessor;

	@Requirement
	private RepositorySystem repositorySystem;

	@Requirement
	private InheritanceAssembler inheritanceAssembler;

	@Requirement
	private ResolutionErrorHandler resolutionErrorHandler;

	private ModelBuilder decoratedModelBuilder;

	private Map<String, Model> resolvedComposites = new HashMap<>();

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request) throws ModelBuildingException {
		ModelBuildingResult result = getModelBuilder().build(request);
		Model effectiveModel = result.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, applyComposites(effectiveModel, request));
	}

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request, ModelBuildingResult result) throws ModelBuildingException {
		ModelBuildingResult newResult = getModelBuilder().build(request, result);
		Model effectiveModel = newResult.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, applyComposites(effectiveModel, request));
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

	private Model applyComposites(Model model, ModelBuildingRequest originalRequest) {
		File baseDir = getBaseDir(originalRequest);

		model.getProperties().entrySet().stream() //
				.filter(entry -> ((String) entry.getKey()).startsWith(PROPERTY_PREFIX)) //
				.map(entry -> getArtifact((String) entry.getValue(), baseDir)) //
				.forEach(artifact -> mergeModel(artifact, model, originalRequest));
		return model;
	}

	private File getBaseDir(ModelBuildingRequest request) {
		String location = request.getModelSource().getLocation();
		File pomFile = new File(location);
		return pomFile.getParentFile();
	}

	private void mergeModel(Artifact artifact, Model model, ModelBuildingRequest originalRequest) {
		String artifactKey = getKey(artifact);
		if (!resolvedComposites.containsKey(artifactKey)) {
			logger.debug("Resolving composite artifact " + artifactKey);
			File artifactFile = artifact.getFile();
			if (artifactFile == null) {
				artifactFile = resolve(artifact);
			}
			Model builtModel = doBuild(originalRequest, artifactFile);
			resolvedComposites.put(artifactKey, builtModel);
		} else {
			logger.debug("Reusing already resolved Model for composite artifact " + artifactKey);
		}
		Model compositeModel = resolvedComposites.get(artifactKey);
		assembleModel(model, compositeModel);
	}

	private File resolve(Artifact artifact) {
		ArtifactResolutionRequest request = getRequest(artifact);
		ArtifactResolutionResult result = repositorySystem.resolve(request);

		File resolvedFile = null;
		if (result.isSuccess()) {
			resolvedFile = result.getArtifacts().iterator().next().getFile();
		} else {
			try {
				resolutionErrorHandler.throwErrors(request, result);
			} catch (ArtifactResolutionException e) {
				throw new RuntimeException(e);
			}
		}
		return resolvedFile;
	}

	private Model doBuild(ModelBuildingRequest originalRequest, File pomFile) {
		ModelBuildingRequest request = new DefaultModelBuildingRequest(originalRequest).setModelSource(null).setPomFile(pomFile);
		try {
			return build(request).getEffectiveModel();
		} catch (ModelBuildingException e) {
			throw new RuntimeException(e);
		}
	}

	private void assembleModel(Model model, Model builtModel) {
		logger.debug("Assembling inheritance from artifact " + builtModel + " onto " + model);

		SimpleProblemCollector problems = new SimpleProblemCollector();
		inheritanceAssembler.assembleModelInheritance(model, builtModel, null, problems);

		if (!problems.isOk()) {
			problems.report(builtModel, logger);
		}
	}

	private ArtifactResolutionRequest getRequest(Artifact artifact) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest();
		request.setArtifact(artifact);
		return request;
	}

	private Artifact getArtifact(String dependency, File baseDir) {
		String groupId;
		String artifactId;
		String version;
		String type = "pom";
		String scope = "compile";
		String classifier = "";
		String[] coordsLocation = dependency.split("@");
		String[] coordinates = coordsLocation[0].split(":");
		if (coordinates.length < 3) {
			throw new IllegalArgumentException("Invalid dependency: " + dependency);
		} else {
			groupId = coordinates[0].trim();
			artifactId = coordinates[1].trim();
			version = coordinates[2].trim();
			if (coordinates.length > 3) {
				type = coordinates[3].trim();
			}
			if (coordinates.length > 4) {
				classifier = coordinates[4].trim();
			}
		}
		Artifact artifact = new DefaultArtifact(groupId, artifactId, version, scope, type, classifier, new DefaultArtifactHandler(type));
		if (coordsLocation.length > 1) {
			String relativePath = coordsLocation[1].trim();
			File location = new File(baseDir, relativePath);
			if (location.isDirectory()) {
				location = modelProcessor.locatePom(location);
				validateLocation(artifact, location);
			}
			artifact.setFile(location);
		}
		return artifact;
	}

	private void validateLocation(Artifact artifact, File location) {
		try {
			Model readModel = modelProcessor.read(location, Collections.emptyMap());
			logger.debug("Read model from location: " + location + " result was: " + readModel);
			String groupId = Optional.ofNullable(readModel.getGroupId()).orElseGet(() -> readModel.getParent().getGroupId());
			String artifactId = readModel.getArtifactId();
			String version = Optional.ofNullable(readModel.getVersion()).orElseGet(() -> readModel.getParent().getVersion());
			if (!groupId.equals(artifact.getGroupId()) || !artifactId.equals(artifact.getArtifactId()) || !version.equals(artifact.getVersion())) {
				throw new IllegalArgumentException("Declared artifact " + artifact + " does not match pom file located at " + location);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private String getKey(Artifact artifact) {
		return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion() + ":" + artifact.getScope() + ":" + artifact.getType() + ":"
				+ artifact.getClassifier();
	}
}
