package org.eluder.coveralls.maven.plugin;

/*
 * #[license]
 * coveralls-maven-plugin
 * %%
 * Copyright (C) 2013 Tapio Rautonen
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * %[license]
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eluder.coveralls.maven.plugin.domain.*;
import org.eluder.coveralls.maven.plugin.httpclient.CoverallsClient;
import org.eluder.coveralls.maven.plugin.json.JsonWriter;
import org.eluder.coveralls.maven.plugin.logging.CoverageTracingLogger;
import org.eluder.coveralls.maven.plugin.logging.JobLogger;
import org.eluder.coveralls.maven.plugin.logging.Logger;
import org.eluder.coveralls.maven.plugin.logging.Logger.Position;
import org.eluder.coveralls.maven.plugin.service.ServiceSetup;
import org.eluder.coveralls.maven.plugin.service.Travis;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public abstract class AbstractCoverallsMojo extends AbstractMojo {

    /**
     * File path to write and submit Coveralls data.
     */
    @Parameter(property = "coverallsFile", defaultValue = "${project.build.directory}/coveralls.json")
    protected File coverallsFile;
    
    /**
     * Url for the Coveralls API.
     */
    @Parameter(property = "coverallsUrl", defaultValue = "https://coveralls.io/api/v1/jobs")
    protected String coverallsUrl;

    /**
     * Directory path for base project.
     */
    @Parameter(property = "projectDirectory", defaultValue = "${basedir}")
    protected File projectDirectory;
    
    /**
     * Source file encoding.
     */
    @Parameter(property = "sourceEncoding", defaultValue = "${project.build.sourceEncoding}")
    protected String sourceEncoding;
    
    /**
     * Coveralls service name.
     */
    @Parameter(property = "serviceName")
    protected String serviceName;
    
    /**
     * Coveralls service job id.
     */
    @Parameter(property = "serviceJobId")
    protected String serviceJobId;
    
    /**
     * Coveralls repository token.
     */
    @Parameter(property = "repoToken")
    protected String repoToken;
    
    /**
     * Git branch name.
     */
    @Parameter(property = "branch")
    protected String branch;

    /**
     * For dynamic project source directory resolution.
     */
    @Component
    protected MavenProject project;

    /**
     *  Holds discovered source directories that get pushed into the SourceLoader.
     */
    private List<File> sourceDirs = new ArrayList<File>();
    
    /**
     * Build timestamp. Must be in 'yyyy-MM-dd HH:mm:ssa' format.
     */
    @Parameter(property = "timestamp", defaultValue = "${timestamp}")
    protected Date timestamp;
    
    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        try {
            getLog().debug("Collecting source directories:");
          addProjectSourceDirectories(this.project);
            for(MavenProject project : this.project.getCollectedProjects()) {
              addProjectSourceDirectories(project);
            }
            createEnvironment().setup();
            CoverageParser parser = createCoverageParser(createSourceLoader());
            Job job = createJob();
            JsonWriter writer = createJsonWriter(job);
            CoverallsClient client = createCoverallsClient();
            List<Logger> reporters = new ArrayList<Logger>();
            reporters.add(new JobLogger(job));
            SourceCallback sourceCallback = createSourceCallbackChain(writer, reporters);
            report(reporters, Position.BEFORE);
            writeCoveralls(writer, sourceCallback, parser);
            report(reporters, Position.AFTER);
            getLog().info(parser.getNumClasses()+" classes covered.");
            submitData(client, writer.getCoverallsFile());
        } catch (MojoFailureException ex) {
            throw ex;
        } catch (ProcessingException ex) {
            throw new MojoFailureException("Processing of input or output data failed", ex);
        } catch (IOException ex) {
            throw new MojoFailureException("IO operation failed", ex);
        } catch (Exception ex) {
            throw new MojoExecutionException("Build error", ex);
        }
    }

  private void addProjectSourceDirectories(MavenProject project) {
    List<String> sources = project.getCompileSourceRoots();
    for(String sourceDir : sources) {
        File sourceDirFile = new File(sourceDir);
        if(sourceDirFile.exists() && sourceDirFile.isDirectory()) {
            getLog().debug("Adding source directory: "+sourceDir);
            sourceDirs.add(sourceDirFile.getAbsoluteFile());
        }
    }
  }

  /**
     * Creates a coverage parser. Must return new instance on every call.
     * 
     * @param sourceLoader the source loader to be used with parser
     * @return new instance of a coverage parser
     */
    protected abstract CoverageParser createCoverageParser(SourceLoader sourceLoader);
    
    /**
     * @return source loader to create source files
     */
    protected SourceLoader createSourceLoader() {
        return new SourceLoader(sourceDirs, sourceEncoding);
    }

    /**
     * @return environment to setup service specific mojo properties
     */
    protected Environment createEnvironment() {
        return new Environment(this, Arrays.asList((ServiceSetup) new Travis()));
    }
    
    /**
     * @return job that describes the coveralls report
     * @throws IOException if an I/O error occurs
     */
    protected Job createJob() throws IOException {
        Git git = new GitRepository(projectDirectory, branch).load();
        return new Job()
            .withRepoToken(repoToken)
            .withServiceName(serviceName)
            .withServiceJobId(serviceJobId)
            .withTimestamp(timestamp)
            .withGit(git);
    }
    
    /**
     * @param job the job describing the coveralls report
     * @return JSON writer that writes the coveralls data
     * @throws IOException if an I/O error occurs
     */
    protected JsonWriter createJsonWriter(final Job job) throws IOException {
        return new JsonWriter(job, coverallsFile);
    }
    
    /**
     * @return http client that submits the coveralls data
     */
    protected CoverallsClient createCoverallsClient() {
        return new CoverallsClient(coverallsUrl);
    }
    
    /**
     * @param writer the JSON writer
     * @return source callback chain for different source handlers
     */
    protected SourceCallback createSourceCallbackChain(final JsonWriter writer, final List<Logger> reporters) {
        SourceCallback chain = writer;
        if (getLog().isDebugEnabled()) {
            CoverageTracingLogger coverageTracingReporter = new CoverageTracingLogger(chain);
            chain = coverageTracingReporter;
            reporters.add(coverageTracingReporter);
        }
        return chain;
    }
    
    private void writeCoveralls(final JsonWriter writer, final SourceCallback sourceCallback, final CoverageParser parser) throws ProcessingException, IOException {
        try {
            getLog().info("Writing Coveralls data to " + writer.getCoverallsFile().getAbsolutePath() + " from coverage report " + parser.getCoverageFile().getAbsolutePath());
            long timestamp = System.currentTimeMillis();
            writer.writeStart();
            parser.parse(sourceCallback);
            writer.writeEnd();
            long duration = System.currentTimeMillis() - timestamp;
            getLog().info("Successfully wrote Coveralls data in " + duration + "ms");
        } finally {
            writer.close();
        }
    }
    
    private void submitData(final CoverallsClient client, final File coverallsFile) throws MojoFailureException, ProcessingException, IOException {
        getLog().info("Submitting Coveralls data to API");
        long timestamp = System.currentTimeMillis();
        CoverallsResponse response = client.submit(coverallsFile);
        long duration = System.currentTimeMillis() - timestamp;
        if (!response.isError()) {
            getLog().info("Successfully submitted Coveralls data in " + duration + "ms for " + response.getMessage());
            getLog().info(response.getUrl());
            getLog().info("*** It might take hours for Coveralls to update the actual coverage numbers for a job");
            getLog().info("    If you see question marks in a report, please be patient");
        } else {
            getLog().error("Failed to submit Coveralls data in " + duration + "ms");
            throw new MojoFailureException("Failed to submit coveralls report: " + response.getMessage());
        }
    }
    
    private void report(final List<Logger> reporters, final Position position) {
        for (Logger reporter : reporters) {
            if (position.equals(reporter.getPosition())) {
                reporter.log(getLog());
            }
        }
    }
}
