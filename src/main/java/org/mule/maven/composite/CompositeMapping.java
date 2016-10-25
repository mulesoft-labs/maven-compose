package org.mule.maven.composite;

import org.codehaus.plexus.component.annotations.Component;
import org.sonatype.maven.polyglot.mapping.Mapping;
import org.sonatype.maven.polyglot.mapping.MappingSupport;

@Component(role = Mapping.class, hint = "default")
public class CompositeMapping extends MappingSupport {

	public CompositeMapping() {
		super("default");
		setPomNames("pom.xml");
		setAcceptLocationExtensions(".xml");
		setAcceptOptionKeys("xml:4.0.0");
		setPriority(1);
	}

}
