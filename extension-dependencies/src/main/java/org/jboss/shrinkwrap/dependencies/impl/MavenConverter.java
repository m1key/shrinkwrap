/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.dependencies.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.shrinkwrap.dependencies.DependencyException;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.artifact.ArtifactType;
import org.sonatype.aether.artifact.ArtifactTypeRegistry;
import org.sonatype.aether.graph.Dependency;
import org.sonatype.aether.graph.Exclusion;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.repository.RepositoryPolicy;
import org.sonatype.aether.util.artifact.ArtifactProperties;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.util.artifact.DefaultArtifactType;

/**
 * An utility class which provides conversion between Maven and Aether objects.
 * It allows creation of Aether object from different objects than Maven objects as well.
 * 
 * @author Benjamin Bentmann
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 * 
 */
public class MavenConverter
{
   private static final Logger log = Logger.getLogger(MavenConverter.class.getName());

   private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?(:([^: ]+))?");

   private static final int DEPENDENCY_GROUP_ID = 1;
   private static final int DEPENDENCY_ARTIFACT_ID = 2;
   private static final int DEPENDENCY_TYPE_ID = 4;
   private static final int DEPENDENCY_CLASSIFIER_ID = 6;
   private static final int DEPENDENCY_VERSION_ID = 8;

   private static final Pattern EXCLUSION_PATTERN = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?");

   private static final int EXCLUSION_GROUP_ID = 1;
   private static final int EXCLUSION_ARTIFACT_ID = 2;
   private static final int EXCLUSION_TYPE_ID = 4;
   private static final int EXCLUSION_CLASSIFIER_ID = 6;

   // disable instantiation
   private MavenConverter()
   {
      throw new AssertionError("Utility class MavenConverter cannot be instantiated.");
   }

   /**
    * Tries to resolve artifact version from internal dependencies from a fetched POM file.
    * If no version is found, it simply returns original coordinates
    * @param dependencyManagement The map including dependency information retrieved from the POM file
    * @param coordinates The coordinates excluding the {@code version} part
    * @return Either coordinates with appended {@code version} or original coordinates
    */
   public static String resolveArtifactVersion(Map<ArtifactAsKey, Dependency> dependencyManagement, String coordinates)
   {
      Matcher m = DEPENDENCY_PATTERN.matcher(coordinates);
      if (!m.matches())
      {
         throw new DependencyException("Bad artifact coordinates"
               + ", expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]][:<version>]");
      }

      ArtifactAsKey key = new ArtifactAsKey(m.group(DEPENDENCY_GROUP_ID), m.group(DEPENDENCY_ARTIFACT_ID),
            m.group(DEPENDENCY_TYPE_ID), m.group(DEPENDENCY_CLASSIFIER_ID));

      if (m.group(DEPENDENCY_VERSION_ID) == null && dependencyManagement.containsKey(key))
      {
         String version = dependencyManagement.get(key).getArtifact().getVersion();
         log.fine("Resolved version " + version + " from the POM file for the artifact: " + coordinates);
         coordinates = coordinates + ":" + version;
      }

      return coordinates;
   }

   /**
    * Converts string coordinates to Aether exclusion object
    * @param coordinates Coordinates specified in the format specified in the format {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]}, an empty string or {@code *} will
    *        match all exclusions, you can pass an {@code *} instead of any part of the coordinates to match all possible values
    * @return Exclusion object based on the coordinates
    * @throws DependencyException If coordinates cannot be converted
    */
   public static Exclusion convertExclusion(String coordinates)
   {
      Validate.notNull(coordinates, "Exclusion string must not be null");

      if (coordinates.length() == 0 || coordinates.equals("*"))
      {
         return new Exclusion("*", "*", "*", "*");
      }

      Matcher m = EXCLUSION_PATTERN.matcher(coordinates);
      if (!m.matches())
      {
         throw new DependencyException("Bad exclusion coordinates"
               + ", expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]]");
      }

      String group = m.group(EXCLUSION_GROUP_ID);
      String artifact = m.group(EXCLUSION_ARTIFACT_ID);
      String type = m.group(EXCLUSION_TYPE_ID);
      String classifier = m.group(EXCLUSION_CLASSIFIER_ID);

      group = (group == null || group.length() == 0) ? "*" : group;
      artifact = (artifact == null || artifact.length() == 0) ? "*" : artifact;
      type = (type == null || type.length() == 0) ? "*" : type;
      classifier = (classifier == null || classifier.length() == 0) ? "*" : classifier;

      return new Exclusion(group, artifact, classifier, type);
   }

   /**
    * Converts a collection of string coordinates to Aether exclusions objects
    * @param coordinates A collection of coordinates specified in the format specified in the format {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]}
    * @return List of Exclusion objects based on the coordinates
    * @throws DependencyException If coordinates cannot be converted
    */
   public static List<Exclusion> convertExclusions(Collection<String> coordinates)
   {
      List<Exclusion> list = new ArrayList<Exclusion>(coordinates.size());
      for (String coords : coordinates)
      {
         list.add(convertExclusion(coords));
      }
      return list;
   }

   /**
    * Converts Maven {@link Repository} to Aether {@link RemoteRepository}
    * @param repository the Maven repository to be converted
    * @return Equivalent remote repository
    */
   public static RemoteRepository convert(org.apache.maven.model.Repository repository)
   {

      return new RemoteRepository()
            .setId(repository.getId())
            .setContentType(repository.getLayout())
            .setUrl(repository.getUrl())
            .setPolicy(true, convertPolicy(repository.getSnapshots()))
            .setPolicy(false, convertPolicy(repository.getReleases()));
   }

   /**
    * Converts Maven {@link Repository} to Aether {@link RemoteRepository}
    * @param repository the Maven repository to be converted
    * @return Equivalent remote repository
    */
   public static RemoteRepository convert(org.apache.maven.settings.Repository repository)
   {
      return new RemoteRepository()
            .setId(repository.getId())
            .setContentType(repository.getLayout())
            .setUrl(repository.getUrl())
            .setPolicy(true, convertPolicy(repository.getSnapshots()))
            .setPolicy(false, convertPolicy(repository.getReleases()));
   }

   /**
    * Converts Maven {@link org.apache.maven.model.Dependency} to Aether {@link org.sonatype.aether.graph.Dependency}
    * @param dependency the Maven dependency to be converted
    * @param registry the Artifact type catalog to determine common artifact properties
    * @return Equivalent Aether dependency
    */
   public static Dependency convert(org.apache.maven.model.Dependency dependency, ArtifactTypeRegistry registry)
   {
      ArtifactType stereotype = registry.get(dependency.getType());
      if (stereotype == null)
      {
         stereotype = new DefaultArtifactType(dependency.getType());
      }

      boolean system = dependency.getSystemPath() != null && dependency.getSystemPath().length() > 0;

      Map<String, String> props = null;
      if (system)
      {
         props = Collections.singletonMap(ArtifactProperties.LOCAL_PATH, dependency.getSystemPath());
      }

      Artifact artifact = new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier(), null, dependency.getVersion(), props, stereotype);

      List<Exclusion> exclusions = new ArrayList<Exclusion>();
      for (org.apache.maven.model.Exclusion e : dependency.getExclusions())
      {
         Exclusion exclusion = new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*");
         exclusions.add(exclusion);
      }

      Dependency result = new Dependency(artifact, dependency.getScope(), dependency.isOptional(), exclusions);

      return result;
   }

   // converts repository policy
   private static RepositoryPolicy convertPolicy(org.apache.maven.model.RepositoryPolicy policy)
   {
      boolean enabled = true;
      String checksums = RepositoryPolicy.CHECKSUM_POLICY_WARN;
      String updates = RepositoryPolicy.UPDATE_POLICY_DAILY;

      if (policy != null)
      {
         enabled = policy.isEnabled();
         if (policy.getUpdatePolicy() != null)
         {
            updates = policy.getUpdatePolicy();
         }
         if (policy.getChecksumPolicy() != null)
         {
            checksums = policy.getChecksumPolicy();
         }
      }

      return new RepositoryPolicy(enabled, updates, checksums);
   }

   // converts repository policy
   private static RepositoryPolicy convertPolicy(org.apache.maven.settings.RepositoryPolicy policy)
   {
      boolean enabled = true;
      String checksums = RepositoryPolicy.CHECKSUM_POLICY_WARN;
      String updates = RepositoryPolicy.UPDATE_POLICY_DAILY;

      if (policy != null)
      {
         enabled = policy.isEnabled();
         if (policy.getUpdatePolicy() != null)
         {
            updates = policy.getUpdatePolicy();
         }
         if (policy.getChecksumPolicy() != null)
         {
            checksums = policy.getChecksumPolicy();
         }
      }

      return new RepositoryPolicy(enabled, updates, checksums);
   }

}
