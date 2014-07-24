package netsize.jenkins.plugins.octopusClient;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;

import java.io.IOException;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class OctopusClientBuilder extends Builder {

	private final String projectName;
	private final String environment;
	private final String version;
	private final boolean waitForDeployment;
	private final String releaseNoteFiles;

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public OctopusClientBuilder(String projectName, String version, String environment, boolean waitForDeployment, String releaseNoteFiles) {
		this.projectName = projectName;
		this.environment = environment;
		this.waitForDeployment = waitForDeployment;
		this.releaseNoteFiles = releaseNoteFiles;
		this.version = version;
	}

	/**
	 * We'll use this from the <tt>config.jelly</tt>.
	 */
	public String getProjectName() {
		return projectName;
	}

	public String getEnvironment() {
		return environment;
	}

	public boolean getWaitForDeployment() {
		return waitForDeployment;
	}

	public String getReleaseNoteFiles() {
		return releaseNoteFiles;
	}

	public String getVersion() {
		return version;
	}

	@Override
	public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

		if (getProjectName().isEmpty()) {
			listener.getLogger().println("Project Name cannot be empty");
			return false;
		}

		String execName = this.getDescriptor().getExecutablePath();

		if (execName == null) {
			listener.getLogger().println("Executable path is empty in global configuration");
			return false;
		} else if (this.getDescriptor().getOctopusUrl().isEmpty()) {
			listener.getLogger().println("Octopus Url cannot be empty");
			return false;
		} else if (this.getDescriptor().getApiKey().isEmpty()) {
			listener.getLogger().println("Octopus Api Key cannot be empty");
			return false;
		} else if (launcher.isUnix()) {
			listener.getLogger().println("Unix is not supported");
			return false;
		} else {
			listener.getLogger().println("Executable path will be " + execName);

			ArgumentListBuilder args = new ArgumentListBuilder();
			EnvVars env = build.getEnvironment(listener);

			// Check that executable path does exist
			FilePath exec = new FilePath(launcher.getChannel(), execName);

			try {
				if (!exec.exists()) {
					listener.fatalError(execName + " doesn't exist");
					return false;
				}
			} catch (IOException e) {
				listener.fatalError("Failed checking for existence of " + execName);
				return false;
			}

			FilePath pwd = build.getModuleRoot();

			if (execName != null) {
				FilePath filePath = pwd.child(execName);
				if (!filePath.exists()) {
					pwd = build.getWorkspace();
				}
			}

			// Adding Executable
			args.add(execName);

			// Type of Command to pass to octo.exe
			args.add("create-release");

			// Adding Project Name Parameter
			args.add("--project=" + this.getProjectName());

			// Adding Project Version Parameter
			if (!this.getVersion().isEmpty())
				args.add("--version=\"" + this.getVersion() + "\"");

			// Adding environment (not mandatory)
			if (!this.getEnvironment().isEmpty())
				args.add("--deployto=\"" + this.getEnvironment() + "\"");

			// Will make the build wait for the end of the deployment
			if (this.getWaitForDeployment())
				args.add("--waitfordeployment");

			// Octopus Url - octo.exe will connect to the REST Api
			if (!this.getDescriptor().getOctopusUrl().isEmpty())
				args.add("--server=" + this.getDescriptor().getOctopusUrl());

			// Octopus API Key - Make sure that associated user is allowed to
			// create releases
			if (!this.getDescriptor().getApiKey().isEmpty())
				args.add("--apiKey=" + this.getDescriptor().getApiKey());

			// Pass release notes to octo.exe
			if (!this.getReleaseNoteFiles().isEmpty()) {
				args.add("--releasenotesfile=\"" + this.getReleaseNoteFiles() + "\"");
			}

			ArgumentListBuilder tmp = new ArgumentListBuilder();

			for (String arg : args.toList()) {
				String argModified = Util.replaceMacro(arg, env);
				argModified = Util.replaceMacro(argModified, build.getBuildVariables());

				tmp.add(argModified);
			}

			args = tmp;

			// If we are on windows, add some elements
			if (!launcher.isUnix()) {
				args.prepend("cmd.exe", "/C");
				args.add("&&", "exit", "%%ERRORLEVEL%%");
			}

			try {
				listener.getLogger().println(String.format("Executing the command %s from %s", args.toStringWithQuote(), pwd));

				// Launch Executable
				int r = launcher.launch().cmds(args).envs(env).stdout(listener.getLogger()).pwd(pwd).join();

				// Return the result of the compilation
				return (r == 0);
			} catch (IOException e) {
				Util.displayIOException(e, listener);
				build.setResult(Result.FAILURE);
				return false;
			}
		}
	}

	// Overridden for better type safety.
	// If your plugin doesn't really define any property on Descriptor,
	// you don't have to do this.
	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	/**
	 * Descriptor for {@link OctopusClientBuilder}. Used as a singleton. The
	 * class is marked as public so that it can be accessed from views.
	 *
	 * <p>
	 * See
	 * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
	@Extension
	// This indicates to Jenkins that this is an implementation of an extension
	// point.
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		private String executablePath;
		private String apiKey;
		private String octopusUrl;

		/**
		 * In order to load the persisted global configuration, you have to call
		 * load() in the constructor.
		 */
		public DescriptorImpl() {
			load();
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
		 *
		 * @param value
		 *            This parameter receives the value that the user has typed.
		 * @return Indicates the outcome of the validation. This is sent to the
		 *         browser.
		 *         <p>
		 *         Note that returning {@link FormValidation#error(String)} does
		 *         not prevent the form from being saved. It just means that a
		 *         message will be displayed to the user.
		 */
		public FormValidation doCheckExecutablePath(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please set an executable path");
			if (value.length() < 4)
				return FormValidation.warning("Executable path is too short");
			return FormValidation.ok();
		}

		public FormValidation doCheckApiKey(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please set an Api Key");
			if (value.length() < 4)
				return FormValidation.warning("Api Key is too short");
			return FormValidation.ok();
		}

		public FormValidation doCheckOctopusUrl(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please set an Octopus Url");
			if (value.length() < 4)
				return FormValidation.warning("Octopus Url is too short");
			return FormValidation.ok();
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			// Indicates that this builder can be used with all kinds of project
			// types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Octopus Create Release";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

			executablePath = formData.getString("executablePath");
			apiKey = formData.getString("apiKey");
			octopusUrl = formData.getString("octopusUrl");

			save();
			return super.configure(req, formData);
		}

		public String getExecutablePath() {
			return executablePath;
		}

		public String getApiKey() {
			return apiKey;
		}

		public String getOctopusUrl() {
			return octopusUrl;
		}
	}
}
