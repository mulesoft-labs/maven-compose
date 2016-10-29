package org.mule.maven.composite;

import java.io.File;

public class Utils {

	static final String ONE_UP = ".." + File.separator;

	public static String relativize(String file, String folder) {
		String[] targetPath = file.split(File.separator);
		String[] anchorPath = folder.split(File.separator);
		int i = 0;
		while (i < targetPath.length && i < anchorPath.length //
				&& targetPath[i].equals(anchorPath[i])) {
			i++;
		}
		StringBuilder relativePathBuilder = new StringBuilder();
		int j = i;
		for (; i < anchorPath.length; i++) {
			relativePathBuilder.append(ONE_UP);
		}

		for (; j < targetPath.length; j++) {
			relativePathBuilder.append(targetPath[j]);
			if (j != targetPath.length - 1) {
				relativePathBuilder.append(File.separator);
			}
		}

		return relativePathBuilder.toString();
	}

}
