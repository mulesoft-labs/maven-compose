package org.mule.maven.composite;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.inheritance.InheritanceAssembler;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.sonatype.maven.polyglot.io.ModelReaderSupport;

@Component(role = ModelReader.class, hint = "default")
public class CompositeModelReader extends ModelReaderSupport {

	private static final String PROPERTY_PREFIX = "maven.compose.";

	@Requirement
	private InheritanceAssembler assembler;

	@Requirement
	private RepositorySystem repositorySystem;

	@Requirement
	private PlexusContainer plexus;

	@Override
	public Model read(Reader input, Map<String, ?> options) throws IOException, ModelParseException {
		Model model = getModelReader().read(input, options);

		Properties properties = model.getProperties();
		properties.keySet().stream() //
				.map(String.class::cast) //
				.filter(key -> key.startsWith(PROPERTY_PREFIX)) //
				.map(properties::get) //
				.map(String.class::cast) //
				.map(this::getArtifact) //
				.forEach(artifact -> mergeModel(artifact, model, options));
		return model;
	}

	private DefaultModelReader getModelReader() {
		if (System.getProperty("debug") != null) {
			List<ComponentDescriptor<?>> readerComponents = plexus.getComponentDescriptorList(ModelReader.class.getName());
			readerComponents.forEach(desc -> {
				System.out.println(desc.getImplementation());
			});
			System.out.println("******");
		}
		return new DefaultModelReader();
	}

	private void mergeModel(Artifact artifact, Model model, Map<String, ?> options) {
		ArtifactResolutionRequest request = getRequest(artifact);
		ArtifactResolutionResult result = repositorySystem.resolve(request);

		result.getArtifacts().stream() //
				.map(Artifact::getFile) //
				.map(this::getReader) //
				.map(reader -> doRead(reader, options)) //
				.forEach(readModel -> assembleModel(model, readModel));
	}

	private void assembleModel(Model model, Model readModel) {
		SimpleProblemCollector problems = new SimpleProblemCollector();
		assembler.assembleModelInheritance(model, readModel, null, problems);
		if (!problems.getErrors().isEmpty()) {
			String errors = problems.getErrors().stream().reduce("", (a, b) -> a + System.lineSeparator() + b);
			String message = "There were problems assembling the inheritance model for imported dependency " + getGav(readModel) + ". Errors: " + errors;
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

	private Model doRead(Reader reader, Map<String, ?> options) {
		try {
			return getModelReader().read(reader, options);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Reader getReader(File file) {
		try {
			return new FileReader(file);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	private static class SimpleProblemCollector implements ModelProblemCollector {
		private Model model;

		private List<String> warnings = new ArrayList<>();

		private List<String> errors = new ArrayList<>();

		private List<String> fatals = new ArrayList<>();

		public SimpleProblemCollector() {
		}

		public SimpleProblemCollector(Model model) {
			this.model = model;
		}

		public Model getModel() {
			return model;
		}

		public List<String> getWarnings() {
			return warnings;
		}

		public List<String> getErrors() {
			return errors;
		}

		public List<String> getFatals() {
			return fatals;
		}

		public void add(ModelProblemCollectorRequest req) {
			switch (req.getSeverity()) {
			case FATAL:
				fatals.add(req.getMessage());
				break;
			case ERROR:
				errors.add(req.getMessage());
				break;
			case WARNING:
				warnings.add(req.getMessage());
				break;
			}
		}
	}

}
