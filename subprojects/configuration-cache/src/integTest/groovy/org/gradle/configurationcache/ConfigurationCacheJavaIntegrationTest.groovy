/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import org.gradle.integtests.fixtures.configurationcache.ConfigurationCacheBuildOperationsFixture
import org.gradle.test.fixtures.archive.ZipTestFixture
import org.junit.Test

import static org.hamcrest.CoreMatchers.containsString

class ConfigurationCacheJavaIntegrationTest extends AbstractConfigurationCacheIntegrationTest {

    protected ConfigurationCacheBuildOperationsFixture configurationCache

    def setup() {
        configurationCache = newConfigurationCacheFixture()
    }

    def "can add JavaExec mainClass convention to automatically find class at execution time"() {
        given:
        def confCache = newConfigurationCacheFixture()
        buildFile """

            plugins {
                id 'java'
            }

            abstract class ComputeMain extends DefaultTask {

                @Classpath
                @InputFiles
                abstract ConfigurableFileCollection getClasspath()

                @OutputFile
                abstract RegularFileProperty getMainClassFile()

                @TaskAction
                def computeMainClass() {
                    // automagically discover main class name from classpath here
                    mainClassFile.get().asFile.text = 'Main'
                }
            }

            def computeMainClass = tasks.register('computeMainClass', ComputeMain) {
                classpath.from(compileJava)
                mainClassFile = layout.buildDirectory.file('mainClass.txt')
            }

            tasks.register('run', JavaExec) {
                classpath = layout.files(compileJava)
                mainClass.convention(
                    computeMainClass.flatMap { it.mainClassFile }.map { it.asFile.text }
                )
            }
        """
        file("src/main/java/Main.java") << """
            class Main { public static void main(String[] args) {
                System.out.println("it works!");
            } }
        """

        when:
        configurationCacheRun 'run'

        then:
        outputContains 'it works!'
        confCache.assertStateStored()

        when:
        configurationCacheRun 'run'

        then:
        outputContains 'it works!'
        confCache.assertStateLoaded()
    }

    def "build on Java project with no source"() {
        given:
        settingsFile << """
            rootProject.name = 'somelib'
        """
        buildFile << """
            plugins { id 'java' }
        """

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":jar", ":assemble", ":build")
        def jarFile = file("build/libs/somelib.jar")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF")

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything is up-to-date
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF")

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":jar", ":assemble", ":build")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF")
    }

    def "build on Java project with no source and additional non-Java files in source directories"() {
        given:
        settingsFile << """
            rootProject.name = 'somelib'
        """
        buildFile << """
            plugins { id 'java' }
        """
        // Should be ignored by the Java source set excludes
        file("src/main/java/thing.kt") << "class Thing(val name: String)"
        file("src/main/java/Gizmo.groovy") << """
            class Gizmo { def foo() {} }
        """

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":jar", ":assemble", ":build")
        def jarFile = file("build/libs/somelib.jar")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF")

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":jar", ":assemble", ":build")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF")
    }

    def "build on Java project with a single source file and no dependencies"() {
        given:
        buildWithSingleSourceFile()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        def classFile = file("build/classes/java/main/Thing.class")
        classFile.isFile()
        def jarFile = file("build/libs/somelib.jar")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class")

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class")

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":compileJava", ":classes", ":jar", ":assemble", ":build")
        classFile.isFile()
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class")

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class")

        when:
        file("src/main/java/thing/NewThing.java") << """
            package thing;

            public class NewThing { }
        """
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":compileJava", ":classes", ":jar", ":assemble", ":build")
        classFile.isFile()
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "thing/NewThing.class")
    }

    def "clean on Java project with a single source file and no dependencies"() {
        given:
        buildWithSingleSourceFile()

        def buildDir = file("build")
        buildDir.mkdirs()

        expect:
        configurationCacheRun "clean"
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":clean")
        !buildDir.exists()

        when:
        buildDir.mkdirs()
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":clean")
        !buildDir.exists()
    }

    def "build on Java project with source files and resources and no dependencies"() {
        given:
        buildWithSourceFilesAndResources()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        def classFile = file("build/classes/java/main/Thing.class")
        classFile.isFile()
        def jarFile = file("build/libs/somelib.jar")
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "answer.txt", "META-INF/some.Service")

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        new ZipTestFixture(jarFile).hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "answer.txt", "META-INF/some.Service")

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":compileJava", ":processResources", ":classes", ":jar", ":assemble", ":build")
        classFile.isFile()
        new ZipTestFixture(jarFile).with {
            hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "answer.txt", "META-INF/some.Service")
            assertFileContent("answer.txt", "42")
        }

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        new ZipTestFixture(jarFile).with {
            hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "answer.txt", "META-INF/some.Service")
            assertFileContent("answer.txt", "42")
        }

        when:
        file("src/main/resources/answer.txt").text = "forty-two"
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":processResources", ":classes", ":jar", ":assemble", ":build")
        classFile.isFile()
        new ZipTestFixture(jarFile).with {
            hasDescendants("META-INF/MANIFEST.MF", "Thing.class", "answer.txt", "META-INF/some.Service")
            assertFileContent("answer.txt", "forty-two")
        }
    }

    def "assemble on Java project with multiple source directories"() {
        given:
        settingsFile << """
            rootProject.name = 'somelib'
        """
        buildFile << """
            plugins { id 'java' }

            sourceSets.main.java.srcDir("src/common/java")
        """
        file("src/common/java/OtherThing.java") << """
            class OtherThing {
            }
        """
        file("src/main/java/Thing.java") << """
            class Thing extends OtherThing {
            }
        """

        when:
        configurationCacheRun "assemble"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":assemble")
        def classFile = file("build/classes/java/main/Thing.class")
        classFile.isFile()
        def jarFile = file("build/libs/somelib.jar")
        jarFile.isFile()

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "assemble"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":assemble")
        classFile.isFile()
        new ZipTestFixture(jarFile).assertContainsFile("Thing.class")
        new ZipTestFixture(jarFile).assertContainsFile("OtherThing.class")
    }

    def "assemble on specific project of multi-project Java build"() {
        given:
        settingsFile << """
            include("a", "b")
        """
        buildFile << """
            allprojects { apply plugin: 'java' }
            project(":b") {
                dependencies {
                    implementation(project(":a"))
                }
            }
        """
        file("a/src/main/java/a/A.java") << """
            package a;
            public class A {}
        """
        file("b/src/main/java/b/B.java") << """
            package b;
            public class B extends a.A {}
        """

        when:
        configurationCacheRun ":b:assemble"

        and:
        file("b/build/classes/java/main/b/B.class").delete()
        def jarFile = file("b/build/libs/b.jar")
        jarFile.isFile()

        and:
        configurationCacheRun "clean"

        and:
        configurationCacheRun ":b:assemble"

        then:
        new ZipTestFixture(jarFile).assertContainsFile("b/B.class")
    }

    def "processResources on Java project honors task outputs"() {
        given:
        buildFile << """
            apply plugin: 'java'
        """
        file("src/main/resources/answer.txt") << "42"

        when:
        configurationCacheRun ":processResources"

        and:
        file("build/resources/main/answer.txt").text == "forty-two"

        and:
        configurationCacheRun ":processResources"

        then:
        configurationCache.assertStateLoaded()
        file("build/resources/main/answer.txt").text == "42"
    }

    def "build on Java project with JUnit tests"() {
        given:
        buildWithSingleSourceFile()
        buildFile << """
            ${jcenterRepository()}
            dependencies { testImplementation "junit:junit:4.13" }
        """
        file("src/test/java/ThingTest.java") << """
            import ${Test.name};

            public class ThingTest {
                @Test
                public void ok() {
                    new Thing();
                }
            }
        """

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        def classFile = file("build/classes/java/main/Thing.class")
        classFile.isFile()
        def testClassFile = file("build/classes/java/test/ThingTest.class")
        testClassFile.isFile()
        def testResults = file("build/test-results/test")
        testResults.isDirectory()
        assertTestsExecuted("ThingTest", "ok")

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        testClassFile.isFile()
        testResults.isDirectory()
        assertTestsExecuted("ThingTest", "ok")

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        this.result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":assemble", ":check", ":build")
        result.assertTasksSkipped(":processResources", ":processTestResources")
        classFile.isFile()
        testClassFile.isFile()
        testResults.isDirectory()
        assertTestsExecuted("ThingTest", "ok")
    }

    def "build on Java application project with no dependencies"() {
        given:
        settingsFile << """
            rootProject.name = 'someapp'
        """
        buildFile << """
            plugins { id 'application' }
            application.mainClass = 'Thing'
        """
        file("src/main/java/Thing.java") << """
            class Thing {
            }
        """

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateStored()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":startScripts", ":distTar", ":distZip", ":assemble", ":check", ":build")
        def classFile = file("build/classes/java/main/Thing.class")
        classFile.isFile()
        def jarFile = file("build/libs/someapp.jar")
        jarFile.isFile()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":startScripts", ":distTar", ":distZip", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped() // everything should be up-to-date
        classFile.isFile()
        jarFile.isFile()

        when:
        configurationCacheRun "clean"

        then:
        configurationCache.assertStateStored()
        !file("build").exists()

        when:
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
        result.assertTasksExecuted(":compileJava", ":processResources", ":classes", ":jar", ":compileTestJava", ":processTestResources", ":testClasses", ":test", ":startScripts", ":distTar", ":distZip", ":assemble", ":check", ":build")
        result.assertTasksNotSkipped(":compileJava", ":classes", ":jar", ":startScripts", ":distTar", ":distZip", ":assemble", ":build")
        classFile.isFile()
        jarFile.isFile()
    }

    def "jar manifest honours build logic inputs"() {
        given:
        buildFile << """
            plugins {
                id 'java'
            }

            jar {
                manifest {
                    attributes('Created-By': providers.systemProperty("creator"))
                }
            }
        """
        def manifestFile = file("build/tmp/jar/MANIFEST.MF")
        def assertCreatedBy = { creator ->
            manifestFile.assertContents(
                containsString("Created-By: $creator")
            )
        }
        def configurationCache = newConfigurationCacheFixture()

        when:
        configurationCacheRun ":jar", "-Dcreator=creator1"

        then:
        assertCreatedBy 'creator1'
        configurationCache.assertStateStored()

        when:
        configurationCacheRun ":jar", "-Dcreator=creator2"

        then:
        assertCreatedBy 'creator2'
        configurationCache.assertStateLoaded()

        when:
        manifestFile.delete()
        configurationCacheRun ":jar", "-Dcreator=creator3"

        then:
        assertCreatedBy 'creator3'
        configurationCache.assertStateLoaded()
    }

    def buildWithSingleSourceFile() {
        settingsFile << """
            rootProject.name = 'somelib'
        """
        buildFile << """
            plugins { id 'java' }
        """
        file("src/main/java/Thing.java") << """
            class Thing {
            }
        """
    }

    def buildWithSourceFilesAndResources() {
        buildWithSingleSourceFile()

        file("src/main/resources/answer.txt") << "42"
        file("src/main/resources/META-INF/some.Service") << "impl"
    }
}
