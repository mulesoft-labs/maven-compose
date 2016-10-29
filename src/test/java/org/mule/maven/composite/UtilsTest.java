package org.mule.maven.composite;

import static org.junit.Assert.*;

import org.junit.Test;

public class UtilsTest {

	@Test
	public void relativePathTests() {
		assertEquals("baz", Utils.relativize("/foo/bar/baz", "/foo/bar"));
		assertEquals("bar/baz", Utils.relativize("/foo/bar/baz", "/foo"));
		assertEquals("../qux", Utils.relativize("/foo/bar/qux", "/foo/bar/baz"));
		assertEquals("../../qux", Utils.relativize("/foo/qux", "/foo/bar/baz"));
		assertEquals("../../../qux", Utils.relativize("/qux", "/foo/bar/baz"));
		assertEquals("../../../qwer/ty/qux", Utils.relativize("/qwer/ty/qux", "/foo/bar/baz"));
	}
}
