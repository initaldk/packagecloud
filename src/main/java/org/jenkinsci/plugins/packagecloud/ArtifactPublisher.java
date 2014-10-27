package org.jenkinsci.plugins.packagecloud;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.*;
import hudson.model.*;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.packagecloud.client.*;
import io.packagecloud.client.Package;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/**
 * The type Artifact publisher.
 */
public class ArtifactPublisher extends Notifier {

    private final String repository;
    private final String username;
    private final String distro;

    /**
     * Instantiates a new Artifact publisher.
     *
     * @param username the username
     * @param repository the repository
     * @param distro the distro
     */
// Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public ArtifactPublisher(String username, String repository, String distro) {
        this.username = username;
        this.repository = repository;
        this.distro = distro;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     * @return the repository
     */
    public String getRepository() {
        return this.repository;
    }

    /**
     * Gets distro.
     *
     * @return the distro
     */
    public String getDistro() {
        return this.distro;
    }

    /**
     * Gets username.
     *
     * @return the username
     */
    public String getUsername() {
        return this.username;
    }

    /**
     * Hold an instance of the Descriptor implementation of this publisher.
     */
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    /**
     * Is supported package.
     *
     * @param filename the filename
     * @return the boolean
     */
    public boolean isSupportedPackage(String filename){
        boolean result = false;
        for (String ext: Package.getSupportedExtensions()){
            if(filename.endsWith(ext)){
               result = true;
            }
        }
        return result;
    }

    private void logger(BuildListener listener, String message){
        listener.getLogger().println(String.format("[org.jenkinsci.plugins.packagecloud.ArtifactPublisher] %s", message));
    }

    /**
     * {@inheritDoc}
     *
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException
     *           {@inheritDoc}
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        PackageCloudHelper packageCloudHelper = new PackageCloudHelper();

        if (build.getResult() == Result.FAILURE || build.getResult() == Result.ABORTED) {
            // build failed. don't post
            return true;
        }

        EnvVars envVars = build.getEnvironment(listener);

        logger(listener, String.format("Job configured with: { repo: %s, distro: %s, username: %s }",
                getRepository(),
                getDistro(),
                getUsername()));

        Collection<Fingerprint> buildFingerprints = build.getBuildFingerprints();

        UsernamePasswordCredentials credentials = packageCloudHelper.getCredentialsForUser(this.getUsername());

        PackageCloud packageCloud = packageCloudHelper.configuredClient(credentials);

        List<Fingerprint> rejectedFingerprints = new ArrayList<Fingerprint>();
        List<Package> packagesToUpload = new ArrayList<Package>();

        // first pass: separate valid packages from non supported packages
        findValidPackages(build, listener, envVars, buildFingerprints, rejectedFingerprints, packagesToUpload);

        // second pass: hydrate any detected dsc's with their respective sourceFiles
        hydrateDebianSourcePackages(build, listener, envVars, packageCloud, rejectedFingerprints, packagesToUpload);

        // final phase: upload all packages
        uploadAllPackages(build, listener, packageCloud, packagesToUpload);

        return true;
    }

    private void uploadAllPackages(AbstractBuild<?, ?> build, BuildListener listener, PackageCloud packageCloud, List<Package> packagesToUpload) {
        for (Package pkg : packagesToUpload) {
            try {
                packageCloud.putPackage(pkg);
            } catch (Exception e) {
                build.setResult(Result.FAILURE);
                logger(listener, "ERROR  " + e.getMessage());
            }
        }
    }

    private void hydrateDebianSourcePackages(AbstractBuild<?, ?> build, BuildListener listener, EnvVars envVars, PackageCloud packageCloud, List<Fingerprint> rejectedFingerprints, List<Package> packagesToUpload) throws IOException {
        for (Package pkg : packagesToUpload) {
            if(pkg.getFilename().endsWith("dsc")){
                hydrateDebianSourcePackage(build, listener, envVars, packageCloud, rejectedFingerprints, pkg);
            }
        }
    }

    private void hydrateDebianSourcePackage(AbstractBuild<?, ?> build, BuildListener listener, EnvVars envVars, PackageCloud packageCloud, List<Fingerprint> rejectedFingerprints, Package pkg) throws IOException {
        Map<String, InputStream> sourceFiles = new HashMap<String, InputStream>();
        logger(listener, "Detected dsc (debian source) file");
        try {
            pkg.getFilestream().mark(0);
            Contents contents = packageCloud.packageContents(pkg);
            // find the files we need from the rejected fingerprints
            for (File file : contents.files) {
                for (Fingerprint fin : rejectedFingerprints) {
                    if (fin.getDisplayName().equals(file.filename)){
                        logger(listener, "found dsc component " + fin.getDisplayName());
                        String expanded = Util.replaceMacro(fin.getFileName(), envVars);
                        FilePath filePath = new FilePath(build.getWorkspace(), expanded);
                        sourceFiles.put(fin.getDisplayName(), filePath.read());
                    }
                }

            }
        } catch (Exception e) {
            build.setResult(Result.FAILURE);
            logger(listener, "ERROR  " + e.getMessage());
        }
        pkg.getFilestream().reset();
        pkg.setSourceFiles(sourceFiles);
    }

    private void findValidPackages(AbstractBuild<?, ?> build, BuildListener listener, EnvVars envVars, Collection<Fingerprint> buildFingerprints, List<Fingerprint> rejectedFingerprints, List<Package> packagesToUpload) throws IOException {
        for (Fingerprint fin : buildFingerprints) {
            if(isSupportedPackage(fin.getDisplayName())){
                logger(listener, "Processing: " + fin.getDisplayName());
                String expanded = Util.replaceMacro(fin.getFileName(), envVars);
                FilePath filePath = new FilePath(build.getWorkspace(), expanded);

                if (fin.getDisplayName().endsWith("dsc")) {
                    Package p = new Package(IOUtils.toByteArray(filePath.read()), this.getRepository(), Integer.valueOf(this.getDistro()));
                    p.setFilename(fin.getDisplayName());
                    packagesToUpload.add(p);
                } else if (this.getDistro().equals("gem")){
                    Package p = new Package(IOUtils.toByteArray(filePath.read()), this.getRepository());
                    p.setFilename(fin.getDisplayName());
                    packagesToUpload.add(p);
                } else {
                    Package p = new Package(filePath.read(), this.getRepository(), Integer.valueOf(this.getDistro()));
                    p.setFilename(fin.getDisplayName());
                    packagesToUpload.add(p);
                }
            } else {
                rejectedFingerprints.add(fin);
            }
        }
    }

    /**
     * BuildStepDescriptor
     */
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        PackageCloudHelper packageCloudHelper = new PackageCloudHelper();

        /**
         * The default constructor.
         */
        public DescriptorImpl() {
            super(ArtifactPublisher.class);
            load();
        }

        /**
         * The name of the plugin to display them on the project configuration web page.
         *
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        @JavaScriptMethod
        public String getDisplayName() {
            return "Push to packagecloud.io";
        }

        /**
         * Return the location of the help document for this publisher.
         *
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         * @see hudson.model.Descriptor#getHelpFile()
         */
        @Override
        public String getHelpFile() {
            return "/plugin/packagecloud/help.html";
        }

        /**
         * Returns true if this task is applicable to the given project.
         *
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         * @see hudson.model.AbstractProject.AbstractProjectDescriptor#isApplicable(Descriptor)
         */
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /**
         * Validates we can find credentials for this username
         *
         * @param value the username
         * @return validation result
         */
        public FormValidation doCheckUsername(@QueryParameter String value) {
            if (packageCloudHelper.getCredentialsForUser(value) == null) {
                return FormValidation.error("Can't find packagecloud.io credentials for this username");
            } else {
                return FormValidation.ok();
            }
        }

        /**
         * Validates existence of credentials to load distributions.
         *
         * @param value form value (ignored)
         * @return validation result
         */
        public FormValidation doCheckDistro(@QueryParameter String value) {
            if (packageCloudHelper.getCredentials().isEmpty()) {
                return FormValidation.error("Can't find any valid packagecloud.io credentials, unable to load distributions");
            } else {
                return FormValidation.ok();
            }
        }

        private ListBoxModel findDistroItems(UsernamePasswordCredentials credentials) throws Exception {
            PackageCloud packageCloud = packageCloudHelper.configuredClient(credentials);
            ListBoxModel items = new ListBoxModel();

            Distributions distributions = packageCloud.getDistributions();

            items.add("Gem", "gem");

            for (Distribution dist : distributions.rpm) {
                for (Version version : dist.versions) {
                    items.add(dist.displayName + " (" + version.displayName + ")", String.valueOf(version.id));
                }
            }
            for (Distribution dist : distributions.deb) {
                for (Version version : dist.versions) {
                    items.add(dist.displayName + " (" + version.displayName + ")", String.valueOf(version.id));
                }
            }
            for (Distribution dist : distributions.dsc) {
                for (Version version : dist.versions) {
                    items.add(dist.displayName + " (" + version.displayName + ")", String.valueOf(version.id));
                }
            }
            return items;
        }

        /**
         * Fills out the distributions dropdown.
         *
         * Since the username is not known (or needed) to retrieve distributions, we iterate through all available credentials
         * until we find a working token.
         *
         * @return the list box model
         */
        public ListBoxModel doFillDistroItems() throws Exception {
            List<UsernamePasswordCredentials> allCredentials = packageCloudHelper.getCredentials();

            for(UsernamePasswordCredentials credentials: allCredentials) {
                try {
                    return findDistroItems(credentials);
                } catch (UnauthorizedException e){
                    System.out.println("org.jenkinsci.plugins.packagecloud.ArtifactPublisher#doFillDistroItems: Credentials invalid, trying another, if available");
                }
            }

            // If we make it this far, we haven't found anything
            ListBoxModel items = new ListBoxModel();
            items.add("No distributions found", "-1");
            return items;
        }
    }
}
