package io.github.badlionmoddinggroup.badliongradle.tasks;

import io.github.badlionmoddinggroup.badliongradle.BadlionGradle;
import io.github.badlionmoddinggroup.badliongradle.util.Profiler;
import io.github.badlionmoddinggroup.badliongradle.util.SourceRemover;
import net.fabricmc.lorenztiny.TinyMappingFormat;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.MethodParameterMapping;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

public class PrepareJarsTask extends DefaultTask {

    public PrepareJarsTask() {
        setGroup("badlion");
    }

    @TaskAction
    public void run() throws IOException {
        String blcVer = BadlionGradle.getGradleExtension(getProject()).badlionVersion;
        Path input = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionOfficial.jar").toPath();
        Path output = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemappedWithMc.jar").toPath();
        Path strippedMinecraftOutput = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemapped.jar").toPath();

        if(strippedMinecraftOutput.toFile().exists()){
            return;
        }

        Profiler.setState("Deleting old cache");
        FileUtils.deleteDirectory(BadlionGradle.getProjectCacheFolder(getProject()));

        retrieveClientJar(BadlionGradle.getGradleExtension(getProject()).badlionVersion);

        remap(input, output, setupRemapper(prepareMappings(blcVer)));

        Profiler.setState("Remove Minecraft");
        SourceRemover.run(new String[]{output.toAbsolutePath().toString(), strippedMinecraftOutput.toAbsolutePath().toString()});
    }

    public void remap(Path input, Path output, TinyRemapper remapper) {
        Profiler.setState("Remap");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
            outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);

            remapper.readInputs(input);

            remapper.apply(outputConsumer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            remapper.finish();
        }
    }

    public TinyRemapper setupRemapper(MappingSet badlionMappings) {
        Profiler.setState("Setup Remapper");
        return TinyRemapper.newRemapper().withMappings(out -> BadlionGradle.iterateClasses(badlionMappings, classMapping -> {
            String owner = classMapping.getFullObfuscatedName();
            out.acceptClass(owner, classMapping.getFullDeobfuscatedName());

            for (MethodMapping methodMapping : classMapping.getMethodMappings()) {
                IMappingProvider.Member method = new IMappingProvider.Member(owner, methodMapping.getObfuscatedName(), methodMapping.getObfuscatedDescriptor());
                out.acceptMethod(method, methodMapping.getDeobfuscatedName());
                for (MethodParameterMapping parameterMapping : methodMapping.getParameterMappings()) {
                    out.acceptMethodArg(method, parameterMapping.getIndex(), parameterMapping.getDeobfuscatedName());
                }
            }

            for (FieldMapping fieldMapping : classMapping.getFieldMappings()) {
                out.acceptField(new IMappingProvider.Member(owner, fieldMapping.getObfuscatedName(), fieldMapping.getType().get().toString()), fieldMapping.getDeobfuscatedName());
            }
        })).ignoreConflicts(true).build();
    }

    public void retrieveClientJar(String version) throws IOException {
        Profiler.setState("Copying original Badlion Jar");
        String clientJarLocation = "CLIENTJARLOCATION IS BROKEN";
        switch (BadlionGradle.OsChecker.getType()) {
            case Windows:
                clientJarLocation = System.getProperty("user.home") + "/AppData/Roaming/.minecraft/versions/BLClient18/BLClient.jar";
                break;
            case Linux:
                clientJarLocation = System.getProperty("user.home") + "/.wine/drive_c/users/" + System.getProperty("user.name") + "/Application Data/.minecraft/versions/BLClient18/BLClient.jar";
        }
        FileUtils.copyFile(new File(clientJarLocation), new File(BadlionGradle.getProjectCacheFolder(getProject()).getAbsolutePath() + "/badlionOfficial.jar"));
    }

    private MappingSet prepareMappings(String blcVer) throws IOException {
        Profiler.setState("Download mappings");
        IOUtils.copy(new URL(BadlionGradle.getGradleExtension(getProject()).minecraftMappingsUrl).openStream(), new FileOutputStream(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "1.8.9.tiny")));
        IOUtils.copy(new URL(BadlionGradle.getGradleExtension(getProject()).badlionMappingsUrl).openStream(), new FileOutputStream(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionIntermediaries.tiny")));

        Profiler.setState("Read Tiny files");
        MappingSet minecraftMappings = TinyMappingFormat.DETECT.read(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "1.8.9.tiny").toPath(), "official", "named");
        MappingSet badlionMappings = TinyMappingFormat.DETECT.read(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionIntermediaries.tiny").toPath(), "official", "intermediary");

        Profiler.setState("MergeMappings");
        BadlionGradle.iterateClasses(minecraftMappings, classMapping -> {
            ClassMapping<?, ?> clazz = badlionMappings.getOrCreateClassMapping(classMapping.getFullObfuscatedName()).setDeobfuscatedName(classMapping.getFullDeobfuscatedName());
            for (MethodMapping methodMapping : classMapping.getMethodMappings()) {
                clazz.getOrCreateMethodMapping(methodMapping.getSignature()).setDeobfuscatedName(methodMapping.getDeobfuscatedName());
            }
            for (FieldMapping fieldMapping : classMapping.getFieldMappings()) {
                clazz.getOrCreateFieldMapping(fieldMapping.getSignature()).setDeobfuscatedName(fieldMapping.getDeobfuscatedName());
            }
        });
        return badlionMappings;
    }
}
