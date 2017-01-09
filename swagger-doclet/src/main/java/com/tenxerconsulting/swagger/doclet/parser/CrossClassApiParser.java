package com.tenxerconsulting.swagger.doclet.parser;

import static com.google.common.base.Objects.equal;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Maps.uniqueIndex;

import java.util.*;

import com.google.common.base.Function;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;
import com.tenxerconsulting.swagger.doclet.DocletOptions;
import com.tenxerconsulting.swagger.doclet.model.*;
import com.tenxerconsulting.swagger.doclet.model.Operation;
import io.swagger.models.*;
import io.swagger.models.Swagger;

/**
 * The CrossClassApiParser represents an api class parser that supports ApiDeclaration being
 * spread across multiple resource classes.
 * @version $Id$
 * @author conor.roche
 */
public class CrossClassApiParser {

	private final DocletOptions options;
	private final ClassDoc classDoc;
	private final Collection<ClassDoc> classes;
	private final String rootPath;
	private final String swaggerVersion;
	private final String apiVersion;
	private final String basePath;

	private final Method parentMethod;
	private final List<ClassDoc> subResourceClasses;
	private final Collection<ClassDoc> typeClasses;

	/**
	 * This creates a CrossClassApiParser for top level parsing
	 * @param options The options for parsing
	 * @param classDoc The class doc
	 * @param classes The doclet classes to document
	 * @param typeClasses Extra type classes that can be used as generic parameters
	 * @param subResourceClasses Sub resource doclet classes
	 * @param swaggerVersion Swagger version
	 * @param apiVersion Overall API version
	 * @param basePath Overall base path
	 */
	public CrossClassApiParser(DocletOptions options, ClassDoc classDoc, Collection<ClassDoc> classes, List<ClassDoc> subResourceClasses,
			Collection<ClassDoc> typeClasses, String swaggerVersion, String apiVersion, String basePath) {
		super();
		this.options = options;
		this.classDoc = classDoc;
		this.classes = classes;
		this.typeClasses = typeClasses;
		this.subResourceClasses = subResourceClasses;
		this.rootPath = ParserHelper.resolveClassPath(classDoc, options);
		this.swaggerVersion = swaggerVersion;
		this.apiVersion = apiVersion;
		this.basePath = basePath;
		this.parentMethod = null;
	}

	/**
	 * This creates a CrossClassApiParser for parsing a subresource
	 * @param options The options for parsing
	 * @param classDoc The class doc
	 * @param classes The doclet classes to document
	 * @param typeClasses Extra type classes that can be used as generic parameters
	 * @param subResourceClasses Sub resource doclet classes
	 * @param swaggerVersion Swagger version
	 * @param apiVersion Overall API version
	 * @param basePath Overall base path
	 * @param parentMethod The parent method that "owns" this sub resource
	 * @param parentResourcePath The parent resource path
	 */
	public CrossClassApiParser(DocletOptions options, ClassDoc classDoc, Collection<ClassDoc> classes, List<ClassDoc> subResourceClasses,
			Collection<ClassDoc> typeClasses, String swaggerVersion, String apiVersion, String basePath, Method parentMethod, String parentResourcePath) {
		super();
		this.options = options;
		this.classDoc = classDoc;
		this.classes = classes;
		this.typeClasses = typeClasses;
		this.subResourceClasses = subResourceClasses;
		this.rootPath = parentResourcePath + ParserHelper.resolveClassPath(classDoc, options);
		this.swaggerVersion = swaggerVersion;
		this.apiVersion = apiVersion;
		this.basePath = basePath;
		this.parentMethod = parentMethod;
	}

	/**
	 * This gets the root jaxrs path of the api resource class
	 * @return The root path
	 */
	public String getRootPath() {
		return this.rootPath;
	}

	/**
	 * This parses the api declarations from the resource classes of the api
	 * @param declarations The map of resource name to declaration which will be added to
	 */
	public void parse(Map<String, PathWrapper> declarations, Swagger swagger) {

		Collection<ClassDoc> allClasses = new ArrayList<ClassDoc>();
		allClasses.addAll(this.classes);
		allClasses.addAll(this.typeClasses);

		ClassDocCache classCache = new ClassDocCache(allClasses);

		// see if this is a resource class, it is if either it has class level @Path or has @GET etc on one of its methods
		// (sub resource classes don't have @Path but will have method annotations)
		if (this.rootPath.isEmpty()) {
			boolean methodFound = false;
			for (MethodDoc method : this.classDoc.methods()) {
				if (ParserHelper.resolveMethodHttpMethod(method) != null) {
					methodFound = true;
					break;
				}
			}
			if (!methodFound) {
				if (this.options.isLogDebug()) {
					System.out.println("ignoring non resource class: " + this.classDoc.name());
				}
				return;
			}
		}

		ClassDoc currentClassDoc = this.classDoc;
		while (currentClassDoc != null) {

			if (this.options.isLogDebug()) {
				System.out.println("processing resource class: " + currentClassDoc.name());
			}

			// read default error type for class
			String defaultErrorTypeClass = ParserHelper.getInheritableTagValue(currentClassDoc, this.options.getDefaultErrorTypeTags(), this.options);
			Type defaultErrorType = ParserHelper.findModel(this.classes, defaultErrorTypeClass);

			Set<ModelWrapper> classModels = new HashSet<>();
			if (this.options.isParseModels() && defaultErrorType != null) {
				classModels.addAll(new ApiModelParser(this.options, this.options.getTranslator(), defaultErrorType, null, this.classes).parse());
			}

			// read class level resource path, priority and description
			String classResourcePriority = ParserHelper.getInheritableTagValue(currentClassDoc, this.options.getResourcePriorityTags(), this.options);
			String classResourceDescription = ParserHelper.getInheritableTagValue(currentClassDoc, this.options.getResourceDescriptionTags(), this.options);

			// check if its a sub resource
			boolean isSubResourceClass = this.subResourceClasses != null && this.subResourceClasses.contains(currentClassDoc);

			// dont process a subresource outside the context of its parent method
			if (isSubResourceClass && this.parentMethod == null) {
				// skip
				if (this.options.isLogDebug()) {
					System.out.println("skipping class as its a sub resource class and we are outside of the parent method context.");
				}
			} else {
				for (MethodDoc method : currentClassDoc.methods()) {

					if (this.options.isLogDebug()) {
						System.out.println("processing method: " + method.name());
					}

					ApiMethodParser methodParser = this.parentMethod == null ? new ApiMethodParser(this.options, this.rootPath, method, allClasses,
							defaultErrorTypeClass) : new ApiMethodParser(this.options, this.parentMethod, method, allClasses, defaultErrorTypeClass);

					Method parsedMethod = methodParser.parse();
					if (parsedMethod == null) {
						if (this.options.isLogDebug()) {
							System.out.println("skipping method: " + method.name() + " as it was not parsed to an api method");
						}
						continue;
					}

					if (parsedMethod.isSubResource()) {
						if (this.options.isLogDebug()) {
							System.out.println("parsing method: " + method.name() + " as a subresource");
						}
						ClassDoc subResourceClassDoc = classCache.findByType(method.returnType());
						// look for a custom return type, this is useful where we return a jaxrs Resource in the method signature
						// which typically returns a different subResource object
						if (subResourceClassDoc == null) {
						    Type customType = ParserHelper.readCustomType(method, this.options, this.classes);
                            subResourceClassDoc = classCache.findByType(customType);
						}
						if (subResourceClassDoc != null) {
							// delete class from the dictionary to handle recursive sub-resources
							Collection<ClassDoc> shrunkClasses = new ArrayList<ClassDoc>(this.classes);
							shrunkClasses.remove(currentClassDoc);
							// recursively parse the sub-resource class
							CrossClassApiParser subResourceParser = new CrossClassApiParser(this.options, subResourceClassDoc, shrunkClasses,
									this.subResourceClasses, this.typeClasses, this.swaggerVersion, this.apiVersion, this.basePath, parsedMethod, parsedMethod.getPath());
							subResourceParser.parse(declarations, swagger);
						}
						continue;
					}

					PathWrapper pathWrapper = declarations.get(parsedMethod.getPath());
					if (pathWrapper == null) {
                        pathWrapper = new PathWrapper(new Path(), Integer.MAX_VALUE, null);
						declarations.put(parsedMethod.getPath(), pathWrapper);
						if (this.options.isLogDebug()) {
							System.out.println("creating new api path for method: " + method.name());
						}
					} else {
						if (this.options.isLogDebug()) {
							System.out.println("reusing api declaration (" + parsedMethod.getPath() + ") for method: " + method.name());
						}
					}

					// look for a priority tag for the resource listing and set on the resource if the resource hasn't had one set
					setApiPriority(classResourcePriority, method, currentClassDoc, pathWrapper);

					// look for a method level description tag for the resource listing and set on the resource if the resource hasn't had one set
					setApiDeclarationDescription(classResourceDescription, method, pathWrapper);

					// find api this method should be added to
                    addOperation(parsedMethod, pathWrapper);

					// add models
					Set<ModelWrapper> methodModels = methodParser.models();

					for (Map.Entry<String, ModelWrapper> entry: addApiModels(classModels, methodModels, method).entrySet()) {
						if (this.options.isLogDebug()) {
							System.out.println("adding model: " + entry.getKey());
							if (swagger.getDefinitions() != null && swagger.getDefinitions().containsKey(entry.getKey())) {
								System.out.println("WARNING: model with name {" + entry.getKey() + "} already exists. Overwriting previous model");
							}
						}

						ModelWrapper modelWrapper = entry.getValue();
						io.swagger.models.Model model = modelWrapper.getModel();

						// add properties to model
                        Map<String, io.swagger.models.properties.Property> propertyMap = new HashMap<>();
                        for (Map.Entry<String, PropertyWrapper> propEntry: modelWrapper.getProperties().entrySet()) {
                            if (model instanceof ModelImpl) {
                            	((ModelImpl) model).addProperty(propEntry.getKey(), propEntry.getValue().getProperty());
							} else {
                            	propertyMap.put(propEntry.getKey(), propEntry.getValue().getProperty());
							}
						}

						if (!(model instanceof ModelImpl)) {
                        	model.setProperties(propertyMap);
						}

					    swagger.addDefinition(entry.getKey(), entry.getValue().getModel());

					}

					if (this.options.isLogDebug()) {
						System.out.println("finished processing for method: " + method.name());
					}
				}
			}
			currentClassDoc = currentClassDoc.superclass();
			// ignore parent object class
			if (!ParserHelper.hasAncestor(currentClassDoc)) {
				break;
			}
		}

	}

	private Map<String, ModelWrapper> addApiModels(Set<ModelWrapper> classModels, Set<ModelWrapper> methodModels, MethodDoc method) {
		methodModels.addAll(classModels);
		Map<String, ModelWrapper> idToModels = Collections.emptyMap();
		try {
			idToModels = uniqueIndex(methodModels, new Function<ModelWrapper, String>() {

				public String apply(ModelWrapper model) {
					return model.getModel().getReference();
				}
			});
		} catch (Exception ex) {
			throw new IllegalStateException(
					"Detected duplicate models, if you use classes with the same name from different packages please set the doclet option -useFullModelIds and retry. The problematic method was : "
							+ method + ", and models were: " + methodModels, ex);
		}
		return idToModels;
	}

	private void setApiPriority(String classResourcePriority, MethodDoc method, ClassDoc currentClassDoc, PathWrapper path) {
		int priorityVal = Integer.MAX_VALUE;
		String priority = ParserHelper.getInheritableTagValue(method, this.options.getResourcePriorityTags(), this.options);
		if (priority != null) {
			priorityVal = Integer.parseInt(priority);
		} else if (classResourcePriority != null) {
			// set from the class
			priorityVal = Integer.parseInt(classResourcePriority);
		}

		if (priorityVal != Integer.MAX_VALUE && path.getPriority() == Integer.MAX_VALUE) {
			path.setPriority(priorityVal);
		}
	}

	private void setApiDeclarationDescription(String classResourceDescription, MethodDoc method, PathWrapper path) {
		String description = ParserHelper.getInheritableTagValue(method, this.options.getResourceDescriptionTags(), this.options);
		if (description == null) {
			description = classResourceDescription;
		}
		if (description != null && path.getDescription() == null) {
			path.setDescription(this.options.replaceVars(description));
		}
	}

	private void addOperation(Method method, PathWrapper pathWrapper) {
		// read api level description
//		String apiDescription = ParserHelper.getInheritableTagValue(cmethod, this.options.getApiDescriptionTags(), this.options);
//
		io.swagger.models.Operation operation = new io.swagger.models.Operation();
//		operation.description(this.options.replaceVars(apiDescription));

		operation.setOperationId(emptyToNull(method.getMethodName()));
		operation.setResponses(method.getResponses());

		operation.setParameters(method.getParameters().isEmpty() ? null : method.getParameters());

		operation.setSummary(emptyToNull(method.getSummary()));
		operation.setDescription(emptyToNull(method.getDescription()));
		operation.consumes(method.getConsumes() == null || method.getConsumes().isEmpty() ? null : method.getConsumes());
		operation.produces(method.getProduces() == null || method.getProduces().isEmpty() ? null : method.getProduces());
//		this.authorizations = method.getAuthorizations();

        operation.deprecated(method.isDeprecated());

        if (parentMethod != null && parentMethod.getMethod() != null) {
            pathWrapper.getPath().set(parentMethod.getMethod().name().toLowerCase(), operation);
		} else {
        	pathWrapper.getPath().set(method.getMethod().name().toLowerCase(), operation);
		}

	}

}
