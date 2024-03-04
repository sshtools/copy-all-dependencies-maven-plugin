package com.sshtools.copyalldependencies;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.DependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Resolves a list of artifacts, eventually transitively, from the
 * specified remote repositories and place the resulting jars in a specific
 * directory.
 */
@Mojo(name = "copy-all-dependencies", requiresProject = false, threadSafe = true, defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class CopyAllDependenciesMojo extends AbstractMojo {

	private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.*)::(.+)");

	/**
	 * A string list of the form
	 * groupId:artifactId:[version[:packaging[:classifier]]].
	 */
	@Parameter(property = "artifacts")
	private List<String> artifacts;

	/**
	 * A single string of the form
	 * groupId:artifactId:[version[:packaging[:classifier]]]<SPACING>groupId:artifactId:[version[:packaging[:classifier]]]....
	 */
	@Parameter(property = "artifactList")
	private String artifactList;

	/**
	 * Default classifier
	 */
	@Parameter(property = "defaultClassifer")
	private String defaultClassifier;

	/**
	 * Default classifier
	 */
	@Parameter(property = "defaultType")
	private String defaultType;

	@Parameter(defaultValue = "true", property = "includeClassifier")
	private boolean includeClassifier;

	@Parameter(defaultValue = "true", property = "resolvedSnapshotVersion")
	private boolean resolvedSnapshotVerssion;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	/**
	 * Repositories in the format id::[layout]::url or just url, separated by comma.
	 * ie.
	 * central::default::https://repo.maven.apache.org/maven2,myrepo::::https://repo.acme.com,https://repo.acme2.com
	 */
	@Parameter(property = "remoteRepositories")
	private String remoteRepositories;
	/**
	 *
	 */
	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	private List<ArtifactRepository> pomRemoteRepositories;

	@Parameter(property = "excludeClassifiers")
	private List<String> excludeClassifiers;

	@Parameter(property = "copyOncePerRuntime", defaultValue = "true")
	private boolean copyOncePerRuntime = true;

	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}/dependency", property = "outputDirectory", required = true)
	private File outputDirectory;

	/**
	 * Skip plugin execution completely.
	 */
	@Parameter(property = "skip", defaultValue = "false")
	private boolean skip;

	@Parameter(property = "includeVersion", defaultValue = "true")
	private boolean includeVersion;

	/**
	 * Download transitively, retrieving the specified artifact and all of its
	 * dependencies.
	 */
	@Parameter(property = "transitive", defaultValue = "true")
	private boolean transitive = true;

	@Parameter(property = "useRemoteRepositories", defaultValue = "true")
	private boolean useRemoteRepositories = true;

	/**
	 * Update policy.
	 */
	@Parameter(property = "updatePolicy")
	private String updatePolicy;
	
	/**
	 * Update policy.
	 */
	@Parameter(property = "checksumPolicy")
	private String checksumPolicy;

	/**
	 * The maven project.
	 */
	@Parameter(required = true, readonly = true, property = "project")
	private MavenProject project;
	
	@Parameter(defaultValue = "false")
	private boolean skipPoms;

	@Component
	private ArtifactResolver artifactResolver;

	@Component
	private DependencyResolver dependencyResolver;

	@Component
	private ArtifactHandlerManager artifactHandlerManager;

	@Component(role = ArtifactRepositoryLayout.class)
	private Map<String, ArtifactRepositoryLayout> repositoryLayouts;

	@Component
	private RepositorySystem repositorySystem;

	private Set<String> artifactsDone = new HashSet<>();
	private DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();

	@Override
	public final void execute() throws MojoExecutionException, MojoFailureException {
		if(!isSkipPoms() || ( isSkipPoms() && ( project == null || !project.getPackaging().equals("pom")))) {
			onExecute();
		}
		else
			getLog().info(String.format("Skipping %s, it is a POM and we are configured to skip these.", project.getArtifact().getArtifactId()));
	}
	
	private Path checkDir(Path resolve) {
		if (!Files.exists(resolve)) {
			try {
				Files.createDirectories(resolve);
			} catch (IOException e) {
				throw new IllegalStateException(String.format("Failed to create %s.", resolve));
			}
		}
		return resolve;
	}

	private void doCoordinate() throws MojoFailureException, MojoExecutionException, IllegalArgumentException,
			DependencyResolverException, ArtifactResolverException {

		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" : updatePolicy,
				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy);
//		ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
//				updatePolicy == null ? ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER : updatePolicy,
//				checksumPolicy == null ? ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE : checksumPolicy);
		List<ArtifactRepository> repoList = new ArrayList<>();

		if (pomRemoteRepositories != null && useRemoteRepositories) {
			repoList.addAll(pomRemoteRepositories);
		}

		if (remoteRepositories != null) {
			// Use the same format as in the deploy plugin id::layout::url
			String[] repos = StringUtils.split(remoteRepositories, ",");
			for (String repo : repos) {
				repoList.add(parseRepository(repo, always));
			}
		}

		ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

		Settings settings = session.getSettings();
		repositorySystem.injectMirror(repoList, settings.getMirrors());
		repositorySystem.injectProxy(repoList, settings.getProxies());
		repositorySystem.injectAuthentication(repoList, settings.getServers());

		buildingRequest.setRemoteRepositories(repoList);

		if (transitive) {
			getLog().debug("Resolving " + coordinate + " with transitive dependencies");
			for (ArtifactResult result : dependencyResolver.resolveDependencies(buildingRequest, coordinate, null)) {

				/*
				 * If the coordinate is for an extension zip, then we only we transitive
				 * dependencies that also have an extension zip
				 */
				handleResult(result);
			}
		} else {
			getLog().debug("Resolving " + coordinate);
			handleResult(artifactResolver.resolveArtifact(buildingRequest, toArtifactCoordinate(coordinate)));
		}

	}

	private void doHandleResult(ArtifactResult result) throws MojoExecutionException {
		Artifact artifact = result.getArtifact();
		File file = artifact.getFile();
		if (file == null || !file.exists()) {
			getLog().warn("Artifact " + artifact.getArtifactId()
					+ " has no attached file. Its content will not be copied in the target model directory.");
			return;
		}

		Path extensionZip = file.toPath();
		try {
			Path target = checkDir(outputDirectory.toPath()).resolve(getFileName(artifact));
			getLog().debug("Copying jar artifact " + artifact.getArtifactId() + " to " + target);
			Files.copy(extensionZip, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy extension to staging area.", e);
		}

	}

	private String getFileName(Artifact a) {
		StringBuilder fn = new StringBuilder();
		fn.append(a.getArtifactId());
		if (includeVersion) {
			fn.append("-");
			if(resolvedSnapshotVerssion) {
				fn.append(a.getVersion());
			}
			else {
				fn.append("SNAPSHOT");
			}
		}
		if (includeClassifier && a.getClassifier() != null && a.getClassifier().length() > 0) {
			fn.append("-");
			fn.append(a.getClassifier());
		}
		fn.append(".");
		fn.append(a.getType());
		return fn.toString();
	}

	private ArtifactRepositoryLayout getLayout(String id) throws MojoFailureException {
		ArtifactRepositoryLayout layout = repositoryLayouts.get(id);

		if (layout == null) {
			throw new MojoFailureException(id, "Invalid repository layout", "Invalid repository layout: " + id);
		}

		return layout;
	}

	private boolean isExclude(Artifact artifact) {
		return artifact != null && artifact.getClassifier() != null && artifact.getClassifier().length() > 0
				&& excludeClassifiers != null && excludeClassifiers.contains(artifact.getClassifier());
	}

	/**
	 * @return {@link #skip}
	 */
	private boolean isSkip() {
		return skip;
	}

	private final boolean isSkipPoms() {
		return skipPoms;
	}

	private void onExecute() throws MojoExecutionException, MojoFailureException {
		if (isSkip()) {
			getLog().info("Skipping plugin execution");
			return;
		}

		List<String> allArtifacts = new ArrayList<>();
		if (artifacts != null)
			allArtifacts.addAll(artifacts);
		if (artifactList != null) {
			for (String a : artifactList.split("\\s+")) {
				a = a.trim();
				if (!a.equals("")) {
					allArtifacts.add(a);
				}
			}
		}

		for (String artifact : allArtifacts) {
			getLog().info("Getting " + artifact);
			String[] tokens = StringUtils.split(artifact, ":");
			if (tokens.length < 3 || tokens.length > 5) {
				throw new MojoFailureException("Invalid artifact, you must specify "
						+ "groupId:artifactId:version[:packaging[:classifier]] " + artifact);
			}
			coordinate.setGroupId(tokens[0]);
			coordinate.setArtifactId(tokens[1]);
			if (tokens.length >= 3) {
				coordinate.setVersion(tokens[2]);
			} else {
				if (project != null)
					coordinate.setVersion(project.getVersion());
				else
					throw new MojoExecutionException("Need a project if not version is specified.");
			}

			if (tokens.length >= 4) {
				coordinate.setType(tokens[3]);
				if (tokens.length == 5) {
					coordinate.setClassifier(tokens[4]);
				} else {
					if (defaultClassifier != null && defaultClassifier.length() > 0)
						coordinate.setClassifier(defaultClassifier);
				}
			} else {
				if (defaultType != null && defaultType.length() > 0)
					coordinate.setType(defaultType);
			}

			try {
				doCoordinate();
			} catch (MojoFailureException | DependencyResolverException | ArtifactResolverException e) {
				throw new MojoExecutionException("Failed to process an artifact.", e);
			}

			coordinate = new DefaultDependableCoordinate();
		}
	}

	private ArtifactRepository parseRepository(String repo, ArtifactRepositoryPolicy policy)
			throws MojoFailureException {
		// if it's a simple url
		String id = "temp";
		ArtifactRepositoryLayout layout = getLayout("default");
		String url = repo;

		// if it's an extended repo URL of the form id::layout::url
		if (repo.contains("::")) {
			Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(repo);
			if (!matcher.matches()) {
				throw new MojoFailureException(repo, "Invalid syntax for repository: " + repo,
						"Invalid syntax for repository. Use \"id::layout::url\" or \"URL\".");
			}

			id = matcher.group(1).trim();
			if (!StringUtils.isEmpty(matcher.group(2))) {
				layout = getLayout(matcher.group(2).trim());
			}
			url = matcher.group(3).trim();
		}
		return new MavenArtifactRepository(id, url, layout, policy, policy);
	}

	private ArtifactCoordinate toArtifactCoordinate(DependableCoordinate dependableCoordinate) {
		ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(dependableCoordinate.getType());
		DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();
		artifactCoordinate.setGroupId(dependableCoordinate.getGroupId());
		artifactCoordinate.setArtifactId(dependableCoordinate.getArtifactId());
		artifactCoordinate.setVersion(dependableCoordinate.getVersion());
		artifactCoordinate.setClassifier(dependableCoordinate.getClassifier());
		artifactCoordinate.setExtension(artifactHandler.getExtension());
		return artifactCoordinate;
	}

	private void handleResult(ArtifactResult result)
			throws MojoExecutionException, DependencyResolverException, ArtifactResolverException {

		Artifact artifact = result.getArtifact();
		String id = toCoords(artifact);

		if (isExclude(artifact)) {
			getLog().info(String.format("Skipping %s because it's classifier is excluded.", id));
			return;
		}

		if (artifactsDone.contains(id))
			return;
		else
			artifactsDone.add(id);
			doHandleResult(result);
	}

	private String toCoords(Artifact artifact) {
		return artifact.getArtifactId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()
				+ (artifact.getClassifier() == null ? "" : ":" + artifact.getClassifier());
	}
}
