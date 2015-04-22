/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.soytree.TemplateDelegateNode.DelTemplateKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;


/**
 * A registry or index of all templates in a Soy tree.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class TemplateRegistry {


  /**
   * Represents a set of delegate templates with the same key (name and variant) and same priority.
   * <p> Note: Per delegate rules, at most one of the templates in each division may be active at
   * render time.
   *
   * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
   */
  public static final class DelegateTemplateDivision {

    /** Map of all delegate templates in this division, by delegate package name. */
    public final Map<String, TemplateDelegateNode> delPackageNameToDelTemplateMap;

    private DelegateTemplateDivision(
        Map<String, TemplateDelegateNode> delPackageNameToDelTemplateMap) {
      this.delPackageNameToDelTemplateMap =
          Collections.unmodifiableMap(Maps.newLinkedHashMap(delPackageNameToDelTemplateMap));
    }
  }


  /**
   * Exception thrown when there's no unique highest-priority active delegate template at render
   * time.
   *
   * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
   */
  public static final class DelegateTemplateConflictException extends Exception {

    public DelegateTemplateConflictException(String errorMsg) {
      super(errorMsg);
    }
  }


  /** Map from basic template name to node. */
  private final ImmutableMap<String, TemplateBasicNode> basicTemplatesMap;

  /** Map from delegate template name to set of keys. */
  private final ImmutableSetMultimap<String, DelTemplateKey> delTemplateNameToKeysMap;

  /** Map from delegate template key to list of DelegateTemplateDivision, where the list is in
   *  descending priority order. */
  private final ImmutableListMultimap<DelTemplateKey, DelegateTemplateDivision> delTemplatesMap;

  /**
   * Constructor.
   * @param soyTree The Soy tree from which to build a template registry.
   */
  public TemplateRegistry(SoyFileSetNode soyTree) {

    // ------ Iterate through all templates to collect data. ------

    ImmutableMap.Builder<String, TemplateBasicNode> basicTemplateBuilder =
        ImmutableMap.builder();
    ImmutableSetMultimap.Builder<String, DelTemplateKey> delTemplateBuilder =
        ImmutableSetMultimap.builder();
    Map<DelTemplateKey, Map<Integer, Map<String, TemplateDelegateNode>>> tempDelTemplatesMap =
        new LinkedHashMap<>();

    for (SoyFileNode soyFile : soyTree.getChildren()) {
      for (TemplateNode template : soyFile.getChildren()) {

        if (template instanceof TemplateBasicNode) {
          // Case 1: Basic template.
          basicTemplateBuilder.put(template.getTemplateName(), (TemplateBasicNode) template);

        } else {
          // Case 2: Delegate template.
          TemplateDelegateNode delTemplate = (TemplateDelegateNode) template;
          DelTemplateKey delTemplateKey = delTemplate.getDelTemplateKey();

          // Add to tempDelTemplateNameToKeysMap.
          String delTemplateName = delTemplate.getDelTemplateName();
          delTemplateBuilder.put(delTemplateName, delTemplateKey);

          // Add to tempDelTemplatesMap.
          int delPriority = delTemplate.getDelPriority();
          String delPackageName = delTemplate.getDelPackageName();

          Map<Integer, Map<String, TemplateDelegateNode>> tempDivisions =
              tempDelTemplatesMap.get(delTemplateKey);
          if (tempDivisions == null) {
            tempDivisions = new LinkedHashMap<>();
            tempDelTemplatesMap.put(delTemplateKey, tempDivisions);
          }

          Map<String, TemplateDelegateNode> tempDivision = tempDivisions.get(delPriority);
          if (tempDivision == null) {
            tempDivision = new LinkedHashMap<>();
            tempDivisions.put(delPriority, tempDivision);
          }

          if (tempDivision.containsKey(delPackageName)) {
            TemplateDelegateNode prevTemplate = tempDivision.get(delPackageName);
            String prevTemplateFilePath =
                prevTemplate.getNearestAncestor(SoyFileNode.class).getFilePath();
            String currTemplateFilePath =
                delTemplate.getNearestAncestor(SoyFileNode.class).getFilePath();
            String errorMsgPrefix = (delPackageName == null) ?
                "Found two default implementations" :
                "Found two implementations in the same delegate package";
            if (currTemplateFilePath != null && currTemplateFilePath.equals(prevTemplateFilePath)) {
              throw SoySyntaxException.createWithoutMetaInfo(String.format(
                  errorMsgPrefix + " for delegate template '%s', both in the file %s.",
                  delTemplateKey, currTemplateFilePath));
            } else {
              throw SoySyntaxException.createWithoutMetaInfo(String.format(
                  errorMsgPrefix + " for delegate template '%s', in files %s and %s.",
                  delTemplateKey, prevTemplateFilePath, currTemplateFilePath));
            }
          }
          tempDivision.put(delPackageName, delTemplate);
        }
      }
    }

    // ------ Build the final data structures. ------

    basicTemplatesMap = basicTemplateBuilder.build();

    ImmutableListMultimap.Builder<DelTemplateKey, DelegateTemplateDivision> delTemplatesMapBuilder
        = ImmutableListMultimap.builder();

    for (DelTemplateKey delTemplateKey : tempDelTemplatesMap.keySet()) {
      Map<Integer, Map<String, TemplateDelegateNode>> tempDivisions =
          tempDelTemplatesMap.get(delTemplateKey);

      ImmutableList.Builder<DelegateTemplateDivision> divisionsBuilder = ImmutableList.builder();

      // Note: List should be in decreasing priority order.
      for (int priority = TemplateNode.MAX_PRIORITY; priority >= 0; priority--) {
        if (!tempDivisions.containsKey(priority)) {
          continue;
        }
        Map<String, TemplateDelegateNode> tempDivision = tempDivisions.get(priority);
        DelegateTemplateDivision division = new DelegateTemplateDivision(tempDivision);
        divisionsBuilder.add(division);
      }

      delTemplatesMapBuilder.putAll(delTemplateKey, divisionsBuilder.build());
    }

    delTemplatesMap = delTemplatesMapBuilder.build();
    delTemplateNameToKeysMap = delTemplateBuilder.build();
  }


  /**
   * Returns a map from basic template name to node.
   */
  public ImmutableMap<String, TemplateBasicNode> getBasicTemplatesMap() {
    return basicTemplatesMap;
  }


  /**
   * Retrieves a basic template given the template name.
   * @param templateName The basic template name to retrieve.
   * @return The corresponding basic template, or null if the template name is not defined.
   */
  @Nullable
  public TemplateBasicNode getBasicTemplate(String templateName) {
    return basicTemplatesMap.get(templateName);
  }


  /**
   * Returns a multimap from delegate template name to set of keys.
   */
  public ImmutableSetMultimap<String, DelTemplateKey> getDelTemplateNameToKeysMap() {
    return delTemplateNameToKeysMap;
  }


  /**
   * Returns a multimap from delegate template key (name and variant)
   * to {@code DelegateTemplateDivision}s, where the list of divisions is in
   * descending priority order.
   */
  public ImmutableListMultimap<DelTemplateKey, DelegateTemplateDivision> getDelTemplatesMap() {
    return delTemplatesMap;
  }


  /** Returns true iff {@code delTemplateName} is registered as a delegate template. */
  public boolean hasDelTemplateNamed(String delTemplateName) {
    return delTemplateNameToKeysMap.containsKey(delTemplateName);
  }


  /**
   * Retrieves the set of {@code DelegateTemplateDivision}s for all variants of a given a delegate
   * template name.
   * @param delTemplateName The delegate template name to retrieve.
   * @return The set of {@code DelegateTemplateDivision}s for all variants.
   */
  public ImmutableSet<DelegateTemplateDivision> getDelTemplateDivisionsForAllVariants(
      String delTemplateName) {
    ImmutableSet<DelTemplateKey> keysForAllVariants = delTemplateNameToKeysMap.get(delTemplateName);
    ImmutableSet.Builder<DelegateTemplateDivision> builder = ImmutableSet.builder();
    for (DelTemplateKey delTemplateKey : keysForAllVariants) {
      builder.addAll(delTemplatesMap.get(delTemplateKey));
    }
    return builder.build();
  }


  /**
   * Selects a delegate template based on the rendering rules, given the delegate template key (name
   * and variant) and the set of active delegate package names.
   *
   * @param delTemplateKey The delegate template key (name and variant) to select an implementation
   *     for.
   * @param activeDelPackageNames The set of active delegate package names.
   * @return The selected delegate template, or null if there are no active implementations.
   * @throws DelegateTemplateConflictException If there are two or more active implementations with
   *     equal priority (unable to select one over the other).
   */
  @Nullable
  public TemplateDelegateNode selectDelTemplate(
      DelTemplateKey delTemplateKey, Set<String> activeDelPackageNames)
      throws DelegateTemplateConflictException {

    TemplateDelegateNode delTemplate = selectDelTemplateHelper(
        delTemplateKey, activeDelPackageNames);

    if (delTemplate == null && !delTemplateKey.variant.isEmpty()) {
      // Fall back to empty variant.
      delTemplate = selectDelTemplateHelper(
          new DelTemplateKey(delTemplateKey.name, ""), activeDelPackageNames);
    }

    return delTemplate;
  }


  /**
   * Private helper for {@code selectDelTemplate()}. Selects a delegate template based on the
   * rendering rules, given the delegate template key (name and variant) and the set of active
   * delegate package names. However, does not fall back to empty variant.
   *
   * @param delTemplateKey The delegate template key (name and variant) to select an implementation
   *     for.
   * @param activeDelPackageNames The set of active delegate package names.
   * @return The selected delegate template, or null if there are no active implementations.
   * @throws DelegateTemplateConflictException
   */
  private TemplateDelegateNode selectDelTemplateHelper(
      DelTemplateKey delTemplateKey, Set<String> activeDelPackageNames)
      throws DelegateTemplateConflictException {
    for (DelegateTemplateDivision division : delTemplatesMap.get(delTemplateKey)) {
      TemplateDelegateNode delTemplate = null;

      for (String delPackageName : division.delPackageNameToDelTemplateMap.keySet()) {
        if (delPackageName != null && ! activeDelPackageNames.contains(delPackageName)) {
          continue;
        }

        if (delTemplate != null) {
          throw new DelegateTemplateConflictException(String.format(
              "For delegate template '%s', found two active implementations with equal" +
                  " priority in delegate packages '%s' and '%s'.",
              delTemplateKey, delTemplate.getDelPackageName(), delPackageName));
        }
        delTemplate = division.delPackageNameToDelTemplateMap.get(delPackageName);
      }

      if (delTemplate != null) {
        return delTemplate;
      }
    }

    return null;
  }
}
