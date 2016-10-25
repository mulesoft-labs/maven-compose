package org.mule.maven.composite;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.codehaus.plexus.logging.Logger;

/**
 * Copied from {@link org.apache.maven.model.building.SimpleProblemCollector}
 *
 */
public class SimpleProblemCollector implements ModelProblemCollector {

	private List<String> warnings = new ArrayList<>();

	private List<String> errors = new ArrayList<>();

	private List<String> fatals = new ArrayList<>();

	public SimpleProblemCollector() {
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

	public boolean isOk() {
		return getErrors().size() + getFatals().size() == 0;
	}

	public void report(Model builtModel, Logger logger) {
		getWarnings().forEach(warning -> logger.warn(warning));

		List<String> allProblems = new LinkedList<>(getErrors());
		allProblems.addAll(getFatals());
		if (!allProblems.isEmpty()) {
			String errors = allProblems.stream().reduce("", (a, b) -> a + System.lineSeparator() + b);
			String message = "There were problems assembling the inheritance model for composite artifact " + getGav(builtModel) + ". Errors: " + errors;
			throw new RuntimeException(message);
		}
	}

	private String getGav(Model model) {
		return model.getGroupId() + model.getArtifactId() + model.getVersion();
	}

}