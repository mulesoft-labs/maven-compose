# maven-compose
This is an extension of maven's model building mechanism that allows users to compose (as in composition vs. inheritance) pom files.

Composition is resolved as a linearization of inheritance. First the default model building is performed. Afterwards, the project's parent pom is set to each of the composite pom files and built again. There is no special trick to solving conflicts, the same rules apply as when inheriting from parent poms.

# Usage
Add the artifact to your `.mvn/extensions.xml` file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>org.mule</groupId>
    <artifactId>maven-compose</artifactId>
    <version>0.0.1-SNAPSHOT</version>
  </extension>
</extensions>
```

This extension is being deployed into MuleSoft's repositories, so you'd need to add them too:
* https://repository.mulesoft.org/releases
* https://repository.mulesoft.org/snapshots

In order to add a pom file as a composite piece of another one, you need to declare a property in your project that is prefixed by `maven-compose.` and that denotes a pom file's coordinates.
Examples:
```xml
<!-- Refer to remotely or locally installed artifacts -->
<properties>
    <maven-compose.foo> org.mule.test:foo:1.0 </maven-compose.foo>
    <maven-compose.bar> org.mule.test:bar:1.0 </maven-compose.bar>
</properties>
```

```xml
<!-- Look for the pom file located at a certain directory named "relative-path" (as you would do with a parent pom) -->
<!-- Also, this is whitespace tolerant so you can actually read it -->
<properties>
    <maven-compose.test>
        org.mule.test:foo:1.0
        @relative-path
    </maven-compose.test>
</properties>
```

```xml
<!-- Use properties -->
<properties>
    <maven-compose.test>
        org.mule.test:foo:${foo-version}
        @relative-path
    </maven-compose.test>
</properties>
```

```xml
<!-- Override properties being used in the referenced pom file -->
<properties>
    <some-foo-property>bar</some-foo-property>
    <maven-compose.test>
        org.mule.test:foo:${foo-version}
        @relative-path
    </maven-compose.test>
</properties>
```
