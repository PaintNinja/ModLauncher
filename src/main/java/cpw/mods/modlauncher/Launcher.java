/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cpw.mods.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.*;
import org.apache.logging.log4j.LogManager;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.LogMarkers.*;

/**
 * Entry point for the ModLauncher.
 */
public class Launcher {
    public static Launcher INSTANCE;
    private final TypesafeMap blackboard;
    private final TransformationServicesHandler transformationServicesHandler;
    private final Environment environment;
    private final TransformStore transformStore;
    private final NameMappingServiceHandler nameMappingServiceHandler;
    private final ArgumentHandler argumentHandler;
    private final LaunchServiceHandler launchService;
    private final LaunchPluginHandler launchPlugins;
    private final ModuleLayerHandler moduleLayerHandler;
    private TransformingClassLoader classLoader;

    private Launcher() {
        INSTANCE = this;
        LogManager.getLogger().info(MODLAUNCHER,"ModLauncher {} starting: java version {} by {}; OS {} arch {} version {}", ()->IEnvironment.class.getPackage().getImplementationVersion(),  () -> System.getProperty("java.version"), ()->System.getProperty("java.vendor"), ()->System.getProperty("os.name"), ()->System.getProperty("os.arch"), ()->System.getProperty("os.version"));
        this.moduleLayerHandler = new ModuleLayerHandler();
        this.launchService = new LaunchServiceHandler(this.moduleLayerHandler);
        this.blackboard = new TypesafeMap();
        this.environment = new Environment(this);
        environment.computePropertyIfAbsent(IEnvironment.Keys.MLSPEC_VERSION.get(), s->IEnvironment.class.getPackage().getSpecificationVersion());
        environment.computePropertyIfAbsent(IEnvironment.Keys.MLIMPL_VERSION.get(), s->IEnvironment.class.getPackage().getImplementationVersion());
        environment.computePropertyIfAbsent(IEnvironment.Keys.MODLIST.get(), s->new ArrayList<>());
        environment.computePropertyIfAbsent(IEnvironment.Keys.SECURED_JARS_ENABLED.get(), k-> ProtectionDomainHelper.canHandleSecuredJars());
        this.transformStore = new TransformStore();
        this.transformationServicesHandler = new TransformationServicesHandler(this.transformStore, this.moduleLayerHandler);
        this.argumentHandler = new ArgumentHandler();
        this.nameMappingServiceHandler = new NameMappingServiceHandler(this.moduleLayerHandler);
        this.launchPlugins = new LaunchPluginHandler(this.moduleLayerHandler);
    }

    public static void main(String... args) {
        var props = System.getProperties();
        if (props.getProperty("java.vm.name").contains("OpenJ9")) {
            System.err.printf("""
            WARNING: OpenJ9 is detected. This is definitely unsupported and you may encounter issues and significantly worse performance.
            For support and performance reasons, we recommend installing a temurin JVM from https://adoptium.net/
            JVM information: %s %s %s
            """, props.getProperty("java.vm.vendor"), props.getProperty("java.vm.name"), props.getProperty("java.vm.version"));
        }
        LogManager.getLogger().info(MODLAUNCHER,"ModLauncher running: args {}", () -> LaunchServiceHandler.hideAccessToken(args));
        LogManager.getLogger().info(MODLAUNCHER, "JVM identified as {} {} {}", props.getProperty("java.vm.vendor"), props.getProperty("java.vm.name"), props.getProperty("java.vm.version"));
        new Launcher().run(args);
    }

    public final TypesafeMap blackboard() {
        return blackboard;
    }

    private void run(String... args) {
        final ArgumentHandler.DiscoveryData discoveryData = this.argumentHandler.setArgs(args);
        this.transformationServicesHandler.discoverServices(discoveryData);
        final var scanResults = this.transformationServicesHandler.initializeTransformationServices(this.argumentHandler, this.environment, this.nameMappingServiceHandler)
                .stream().collect(Collectors.groupingBy(ITransformationService.Resource::target));
        scanResults.getOrDefault(IModuleLayerManager.Layer.PLUGIN, List.of())
                .stream()
                .<SecureJar>mapMulti((resource, action) -> resource.resources().forEach(action))
                .forEach(np->this.moduleLayerHandler.addToLayer(IModuleLayerManager.Layer.PLUGIN, np));
        this.moduleLayerHandler.buildLayer(IModuleLayerManager.Layer.PLUGIN);
        final var gameResults = this.transformationServicesHandler.triggerScanCompletion(this.moduleLayerHandler)
                .stream().collect(Collectors.groupingBy(ITransformationService.Resource::target));
        final var gameContents = Stream.of(scanResults, gameResults)
                .flatMap(m -> m.getOrDefault(IModuleLayerManager.Layer.GAME, List.of()).stream())
                .<SecureJar>mapMulti((resource, action) -> resource.resources().forEach(action))
                .toList();
        gameContents.forEach(j->this.moduleLayerHandler.addToLayer(IModuleLayerManager.Layer.GAME, j));
        this.transformationServicesHandler.initialiseServiceTransformers();
        this.launchPlugins.offerScanResultsToPlugins(gameContents);
        this.launchService.validateLaunchTarget(this.argumentHandler);
        final TransformingClassLoaderBuilder classLoaderBuilder = this.launchService.identifyTransformationTargets(this.argumentHandler);
        this.classLoader = this.transformationServicesHandler.buildTransformingClassLoader(this.launchPlugins, classLoaderBuilder, this.environment, this.moduleLayerHandler);
        Thread.currentThread().setContextClassLoader(this.classLoader);
        this.launchService.launch(this.argumentHandler, this.moduleLayerHandler.getLayer(IModuleLayerManager.Layer.GAME).orElseThrow(), this.classLoader, this.launchPlugins);
    }

    public Environment environment() {
        return this.environment;
    }

    Optional<ILaunchPluginService> findLaunchPlugin(final String name) {
        return launchPlugins.get(name);
    }

    Optional<ILaunchHandlerService> findLaunchHandler(final String name) {
        return launchService.findLaunchHandler(name);
    }

    Optional<BiFunction<INameMappingService.Domain, String, String>> findNameMapping(final String targetMapping) {
        return nameMappingServiceHandler.findNameTranslator(targetMapping);
    }

    public Optional<IModuleLayerManager> findLayerManager() {
        return Optional.ofNullable(this.moduleLayerHandler);
    }
}
