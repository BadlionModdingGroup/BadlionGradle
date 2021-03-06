package io.github.badlionmoddinggroup.badliongradle.task;

import io.github.badlionmoddinggroup.badliongradle.BadlionGradle;
import io.github.badlionmoddinggroup.badliongradle.provider.BadlionProvider;
import io.github.badlionmoddinggroup.badliongradle.provider.MinecraftProvider;
import io.github.badlionmoddinggroup.badliongradle.util.Profiler;
import io.github.badlionmoddinggroup.badliongradle.util.SourceRemover;
import net.fabricmc.lorenztiny.TinyMappingFormat;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.MethodParameterMapping;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.zeroturnaround.zip.commons.IOUtils;

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
        Path outputIntermediary = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemappedIntermediaryWithMc.jar").toPath();
        Path outputNamed = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemappedNamedWithMc.jar").toPath();
        Path badlionRemappedIntermediary = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemappedIntermediary.jar").toPath();
        Path badlionRemappedNamed = BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionRemappedNamed.jar").toPath();
        getProject().getLogger().info("Preparing " + blcVer);

        generateClient(blcVer, BadlionGradle.getGradleExtension(getProject()).minecraftVersion);

        if (badlionRemappedIntermediary.toFile().exists()) {
            return;
        }

        remap(input, outputIntermediary, setupRemapper(prepareMappings(blcVer, true)));
        remap(input, outputNamed, setupRemapper(prepareMappings(blcVer, false)));

        Profiler.setState("Remove Minecraft");
        SourceRemover.run(new String[]{outputIntermediary.toAbsolutePath().toString(), badlionRemappedIntermediary.toAbsolutePath().toString()});
        SourceRemover.run(new String[]{outputNamed.toAbsolutePath().toString(), badlionRemappedNamed.toAbsolutePath().toString()});
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

    /**
     *
     * @param badlionVersion the version of Badlion to retrieve
     */
    public void generateClient(String badlionVersion, String minecraftVersion){
        MinecraftProvider minecraftProvider = new MinecraftProvider(minecraftVersion, getProject());
        BadlionGradle.getGradleExtension(getProject()).badlionProvider = new BadlionProvider(badlionVersion, getProject(), minecraftProvider);
    }

    private MappingSet prepareMappings(String blcVer, boolean onlyIntermediary) throws IOException {
        Profiler.setState("Download mappings");
        IOUtils.copy(new URL(BadlionGradle.getGradleExtension(getProject()).minecraftMappingsUrl).openStream(), new FileOutputStream(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "1.8.9.tiny")));
        IOUtils.copy(new URL(BadlionGradle.getGradleExtension(getProject()).badlionIntermediariesUrl).openStream(), new FileOutputStream(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionIntermediaries.tiny")));
        IOUtils.copy(new URL(BadlionGradle.getGradleExtension(getProject()).badlionMappingsUrl).openStream(), new FileOutputStream(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionMappings.tiny")));

        Profiler.setState("Read Tiny files");
        MappingSet minecraftMappings = TinyMappingFormat.DETECT.read(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "1.8.9.tiny").toPath(), "official", "named");
        if(onlyIntermediary){
            System.out.println("Reading Intermediary BLC Mappings");
        }else{
            System.out.println("Reading Named & Intermediary BLC Mappings");
        }
        MappingSet badlionMappings = onlyIntermediary ? TinyMappingFormat.DETECT.read(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionIntermediaries.tiny").toPath(), "official", "intermediary") : TinyMappingFormat.DETECT.read(BadlionGradle.getVersionCacheFile(getProject(), blcVer, "badlionMappings.tiny").toPath(), "official", "named");

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
