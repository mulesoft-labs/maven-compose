package org.mule.maven.composite;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.building.Result;
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
	private ResolutionErrorHandler resolutionErrorHandler;

	private ModelBuilder decoratedModelBuilder;

	/*
	 * set of structures to hold previously resolved artifacts/models (to avoid
	 * relative path issues in multi module projects) and to avoid resolving
	 * twice the same extension
	 */

	private Map<String, Artifact> artifacts = new HashMap<>();

	private Map<String, Model> resolvedComposites = new HashMap<>();

	private Map<String, Set<String>> assembledComposites = new HashMap<>();

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request) throws ModelBuildingException {
		ModelBuildingResult result = getModelBuilder().build(request);
		Model effectiveModel = result.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, assembleComposites(effectiveModel, request));
	}

	@Override
	public ModelBuildingResult build(ModelBuildingRequest request, ModelBuildingResult result) throws ModelBuildingException {
		ModelBuildingResult newResult = getModelBuilder().build(request, result);
		Model effectiveModel = newResult.getEffectiveModel();
		return DefaultModelBuildingResult.from(result, assembleComposites(effectiveModel, request));
	}

	@Override
	public Result<? extends Model> buildRawModel(File pomFile, int validationLevel, boolean locationTracking) {
		return getModelBuilder().buildRawModel(pomFile, validationLevel, locationTracking);
	}

	private Model assembleComposites(Model model, ModelBuildingRequest originalRequest) throws ModelBuildingException {
		File baseDir = getBaseDir(originalRequest);

		List<Artifact> artifactsToMerge = model.getProperties().entrySet().stream() //
				.filter(entry -> ((String) entry.getKey()).startsWith(PROPERTY_PREFIX)) //
				.map(entry -> getArtifact((String) entry.getValue(), baseDir)) //
				.collect(Collectors.toList());

		for (Artifact artifact : artifactsToMerge) {
			model = mergeModel(artifact, model, originalRequest);
		}
		return model;
	}

	private File getBaseDir(ModelBuildingRequest request) {
		String location = request.getModelSource().getLocation();
		File pomFile = new File(location);
		return pomFile.getParentFile();
	}

	private Model mergeModel(Artifact artifact, Model model, ModelBuildingRequest originalRequest) throws ModelBuildingException {
		String artifactKey = getKey(artifact);
		if (!resolvedComposites.containsKey(artifactKey)) {
			logger.debug("Resolving composite artifact " + artifactKey);
			File artifactFile = artifact.getFile();
			if (artifactFile == null) {
				artifactFile = resolve(artifact);
			}
			resolvedComposites.put(artifactKey, doBuild(originalRequest, artifactFile));
		} else {
			logger.debug("Reusing already resolved Model for composite artifact " + artifactKey);
		}
		Model compositeModel = resolvedComposites.get(artifactKey);

		if (!wasCompositeAssembled(model, artifactKey)) {
			addAssembledComposite(model, artifactKey);
			return assembleModel(model, compositeModel, artifact, originalRequest);
		} else {
			return model;
		}
	}

	private Model assembleModel(Model baseModel, Model compositeModel, Artifact artifact, ModelBuildingRequest originalRequest) throws ModelBuildingException {
		logger.debug("Assembling inheritance from artifact " + compositeModel + " onto " + baseModel);

		File baseModelLocation = new File(originalRequest.getModelSource().getLocation()).getParentFile();
		Parent parent = createParentFrom(artifact, baseModelLocation);
		return buildReplacingParent(baseModel, parent, originalRequest);
	}

	private Model buildReplacingParent(Model model, Parent parent, ModelBuildingRequest originalRequest) throws ModelBuildingException {
		DefaultModelBuildingRequest request = new DefaultModelBuildingRequest(originalRequest);

		Model modelWithNewParent = model.clone();
		modelWithNewParent.setParent(parent);
		request.setRawModel(modelWithNewParent);
		Model newEffectiveModel = build(request).getEffectiveModel();

		// restore original parent
		Parent originalParent = model.getParent() != null ? model.getParent().clone() : null;
		newEffectiveModel.setParent(originalParent);

		return newEffectiveModel;
	}

	private Parent createParentFrom(Artifact artifact, File baseModelLocation) {
		Parent parent = new Parent();
		parent.setGroupId(artifact.getGroupId());
		parent.setArtifactId(artifact.getArtifactId());
		parent.setVersion(artifact.getVersion());
		parent.setRelativePath(Utils.relativize(artifact.getFile().getAbsolutePath(), baseModelLocation.getAbsolutePath()));
		logger.debug("Relative path from " + artifact.getFile().getAbsolutePath() + " to " + baseModelLocation.getAbsolutePath() + " = " + parent.getRelativePath());
		return parent;
	}

	private File resolve(Artifact artifact) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest().setArtifact(artifact);
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
			return build(request).getRawModel();
		} catch (ModelBuildingException e) {
			throw new RuntimeException(e);
		}
	}

	private Artifact getArtifact(String dependency, File baseDir) {
		String[] coordsLocation = dependency.split("@");
		if (!artifacts.containsKey(coordsLocation[0])) {
			String groupId, artifactId, version, type = "pom", scope = "compile", classifier = "";
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
			artifacts.put(coordsLocation[0], artifact);
		}
		return artifacts.get(coordsLocation[0]);
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

	private ModelBuilder getModelBuilder() {
		if (decoratedModelBuilder == null) {
			try {
				String typeName = ModelBuilder.class.getName();
				List<Object> components = plexus.lookupList(typeName);
				logger.debug(typeName + " available components: " + components);
				ModelBuilder component = (ModelBuilder) components.stream() //
						.filter(x -> x != this).findFirst() //
						.orElseThrow(() -> new IllegalStateException("There should be another implementation of " + typeName));
				logger.debug("Using " + typeName + " implementation as default: " + component);
				decoratedModelBuilder = component;
			} catch (ComponentLookupException e) {
				throw new RuntimeException("Error retrieving default ModelBuilder component", e);
			}
		}
		return decoratedModelBuilder;
	}

	private void addAssembledComposite(Model model, String artifactKey) {
		String key = getKey(model);
		if (!assembledComposites.containsKey(key)) {
			assembledComposites.put(key, new HashSet<>());
		}
		assembledComposites.get(key).add(artifactKey);
	}

	private boolean wasCompositeAssembled(Model model, String artifactKey) {
		return assembledComposites.containsKey(getKey(model)) ? assembledComposites.get(getKey(model)).contains(artifactKey) : false;
	}

	private String getKey(Artifact artifact) {
		return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion() + ":" + artifact.getScope() + ":" + artifact.getType() + ":"
				+ artifact.getClassifier();
	}

	private String getKey(Model artifact) {
		return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
	}
}
