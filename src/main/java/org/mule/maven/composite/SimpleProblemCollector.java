package org.mule.maven.composite;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;

class SimpleProblemCollector implements ModelProblemCollector {
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