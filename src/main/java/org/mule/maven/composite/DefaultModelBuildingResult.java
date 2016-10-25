package org.mule.maven.composite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;

/**
 * Copied from
 * {@link org.apache.maven.model.building.DefaultModelBuildingResult}
 *
 */
public class DefaultModelBuildingResult implements ModelBuildingResult {

	private Model effectiveModel;

	private List<String> modelIds;

	private Map<String, Model> rawModels;

	private Map<String, List<Profile>> activePomProfiles;

	private List<Profile> activeExternalProfiles;

	private List<ModelProblem> problems;

	private DefaultModelBuildingResult() {
		modelIds = new ArrayList<String>();
		rawModels = new HashMap<String, Model>();
		activePomProfiles = new HashMap<String, List<Profile>>();
		activeExternalProfiles = new ArrayList<Profile>();
		problems = new ArrayList<ModelProblem>();
	}

	public static ModelBuildingResult from(ModelBuildingResult result, Model newEffectiveModel) {
		DefaultModelBuildingResult newResult = new DefaultModelBuildingResult();

		// copy result into newResult
		newResult.setActiveExternalProfiles(result.getActiveExternalProfiles());
		result.getModelIds().forEach(id -> {
			newResult.addModelId(id);
			newResult.setRawModel(id, result.getRawModel(id));
			newResult.setActivePomProfiles(id, result.getActivePomProfiles(id));
		});
		newResult.setProblems(result.getProblems());

		newResult.setEffectiveModel(newEffectiveModel);

		return newResult;
	}

	@Override
	public Model getEffectiveModel() {
		return effectiveModel;
	}

	public DefaultModelBuildingResult setEffectiveModel(Model model) {
		this.effectiveModel = model;

		return this;
	}

	@Override
	public List<String> getModelIds() {
		return modelIds;
	}

	public DefaultModelBuildingResult addModelId(String modelId) {
		if (modelId == null) {
			throw new IllegalArgumentException("no model identifier specified");
		}

		modelIds.add(modelId);

		return this;
	}

	@Override
	public Model getRawModel() {
		return rawModels.get(modelIds.get(0));
	}

	@Override
	public Model getRawModel(String modelId) {
		return rawModels.get(modelId);
	}

	public DefaultModelBuildingResult setRawModel(String modelId, Model rawModel) {
		if (modelId == null) {
			throw new IllegalArgumentException("no model identifier specified");
		}

		rawModels.put(modelId, rawModel);

		return this;
	}

	@Override
	public List<Profile> getActivePomProfiles(String modelId) {
		return activePomProfiles.get(modelId);
	}

	public DefaultModelBuildingResult setActivePomProfiles(String modelId, List<Profile> activeProfiles) {
		if (modelId == null) {
			throw new IllegalArgumentException("no model identifier specified");
		}

		if (activeProfiles != null) {
			this.activePomProfiles.put(modelId, new ArrayList<Profile>(activeProfiles));
		} else {
			this.activePomProfiles.remove(modelId);
		}

		return this;
	}

	@Override
	public List<Profile> getActiveExternalProfiles() {
		return activeExternalProfiles;
	}

	public DefaultModelBuildingResult setActiveExternalProfiles(List<Profile> activeProfiles) {
		if (activeProfiles != null) {
			this.activeExternalProfiles = new ArrayList<Profile>(activeProfiles);
		} else {
			this.activeExternalProfiles.clear();
		}

		return this;
	}

	@Override
	public List<ModelProblem> getProblems() {
		return problems;
	}

	public DefaultModelBuildingResult setProblems(List<ModelProblem> problems) {
		if (problems != null) {
			this.problems = new ArrayList<ModelProblem>(problems);
		} else {
			this.problems.clear();
		}

		return this;
	}

}
