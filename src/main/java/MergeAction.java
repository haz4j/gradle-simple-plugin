import com.intellij.ide.SaveAndSyncHandler;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class MergeAction extends AnAction {

    private static List<String> SKIP_METHODS = Arrays.asList("registerNatives", "Object", "getClass", "hashCode",
            "equals", "clone", "toString", "notify", "notifyAll", "wait", "finalize");

    private Project project;
    private PsiClass currentClass;

    @Override
    public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
        project = anActionEvent.getProject();
        List<PsiFile> psiFiles = getFiles(anActionEvent);
        psiFiles.forEach(this::process);
    }

    @NotNull
    private List<PsiFile> getFiles(@NotNull AnActionEvent anActionEvent) {
        VirtualFile file = anActionEvent.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            log("Can't find file");
            return Collections.emptyList();
        }
        if (file.isDirectory()) {
            return getFilesFromDirectory(file);
        } else {
            return getFile(anActionEvent);
        }
    }

    @NotNull
    private List<PsiFile> getFile(@NotNull AnActionEvent anActionEvent) {
        PsiFile psiFile = anActionEvent.getData(CommonDataKeys.PSI_FILE);
        if (psiFile == null) {
            return Collections.emptyList();
        } else {
            return Collections.singletonList(psiFile);
        }
    }

    @NotNull
    private List<PsiFile> getFilesFromDirectory(@NotNull VirtualFile file) {
        try {
            List<Path> collect = Files.walk(Paths.get(file.getPath()))
                    .filter(s -> s.toString().endsWith("Impl.java"))
                    .collect(toList());
            return collect.stream()
                    .map(pathToPsiFile())
                    .filter(Objects::nonNull)
                    .collect(toList());
        } catch (IOException e) {
            log(e.getMessage());
            return Collections.emptyList();
        }
    }

    @NotNull
    private Function<Path, PsiFile> pathToPsiFile() {
        return col -> {
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(new File(col.toUri()));
            if (vFile == null) {
                return null;
            } else {
                return PsiManager.getInstance(project).findFile(vFile);
            }
        };
    }

    private PsiClass getInterface(){
        PsiClass[] interfaces = currentClass.getInterfaces();
        if (interfaces.length != 1) {
            return null;
        }
        return interfaces[0];
    }

    private void process(@NotNull PsiFile psiFile) {
        psiFile.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                super.visitClass(aClass);
                currentClass = aClass;
            }
        });

        PsiClass currentInterface = getInterface();
        if (currentInterface == null) {
            log("Can't find the only interface");
            return;
        }

        deleteInheritDoc();

        if (currentInterface.getDocComment() != null) {
            addComment(currentInterface, currentClass);
        }

        mergeMethods(currentInterface);

        changePackage(currentClass, currentInterface);

        Path path = Paths.get(currentInterface.getContainingFile().getVirtualFile().getPath());

        deleteFile(currentInterface);
        deleteFile(currentClass);

        save();

        updateClassFile(path);

        refresh();
    }

    private void updateClassFile(Path path) {
        String classText = currentClass.getContainingFile().getText();
        String[] split = classText.split("\\n");
        List<String> lines = Stream.of(split)
                .filter(line -> !line.contains("@Override"))
                .map(line -> line
                        .replaceAll("^(\\s*?)default\\s(.*)\\(", "$1public $2(")
                        .replaceAll("public class (.*)Impl implements (.*)", "public class $1 {")
                )
                .collect(toList());

        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log(e.getMessage());
        }
    }

    private void refresh() {
        try {
            Thread.sleep(500L);
            SaveAndSyncHandler.getInstance().refreshOpenFiles();
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
            ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();
        } catch (InterruptedException e) {
            log(e.getMessage());
        }
    }

    private void save() {
        project.save();
        FileDocumentManager.getInstance().saveAllDocuments();
        ProjectManagerEx.getInstanceEx().blockReloadingProjectOnExternalChanges();
    }

    private void mergeMethods(PsiClass currentInterface) {
        PsiMethod previousClassMethod = null;
        for (PsiMethod intMethod : currentInterface.getAllMethods()) {
            if (SKIP_METHODS.contains(intMethod.getName())) {
                continue;
            }
            List<PsiMethod> classMethods = findMethodImpls(intMethod, currentClass);

            if (classMethods.size() > 1) {
                log("more than 1 imp of method - " + intMethod.getName());
                log(classMethods.stream().map(PsiElement::getText).collect(Collectors.joining(", ")));
                continue;
            }
            if (classMethods.size() == 0) {
                copyMethod(intMethod, previousClassMethod);
            } else {
                PsiMethod classMethod = classMethods.get(0);
                if (intMethod.getDocComment() != null) {
                    addComment(intMethod, classMethod);
                }
                previousClassMethod = classMethod;
            }
        }
    }

    private void deleteInheritDoc() {
        Runnable r = () -> {
            deleteLastChildContainingText(currentClass);
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void deleteLastChildContainingText(PsiElement parent) {
        PsiElement[] childs = parent.getChildren();

        for (PsiElement child : childs) {
            deleteLastChildContainingText(child);
            String cleanedText = child.getText().replaceAll("\\*", "").replaceAll("//", "").trim();
            if (cleanedText.matches("\\s?\\{\\@inheritDoc\\}\\s?")) {
                parent.delete();
            }
        }
    }

    private void copyMethod(PsiMethod intMethod, PsiMethod previousClassMethod) {
        Runnable r = () -> {
            PsiElement newMethod = intMethod.copy();
            final PsiElement variableParent = previousClassMethod == null ? null : previousClassMethod.getParent();

            variableParent.addAfter(newMethod, previousClassMethod);
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void changePackage(PsiClass containingClass, PsiClass anInterface) {
        PsiJavaFile containingFile = (PsiJavaFile) containingClass.getContainingFile();
        PsiPackageStatement packStatement = containingFile.getPackageStatement();
        String newPackage = ((PsiJavaFile) anInterface.getContainingFile()).getPackageStatement().getPackageName();
        PsiPackageStatement newStatement = JavaPsiFacade.getElementFactory(project).createPackageStatement(newPackage);
        WriteCommandAction.runWriteCommandAction(project, () -> {
            packStatement.replace(newStatement);
        });
        String fullImportStatement = newPackage + "." + anInterface.getName();

        Optional<PsiImportStatementBase> wrongImport = Stream.of(containingFile.getImportList().getAllImportStatements()).filter(psiImportStatementBase -> psiImportStatementBase.getImportReference().getCanonicalText().equals(fullImportStatement)).findAny();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            wrongImport.ifPresent(PsiElement::delete);
        });

    }

    private List<PsiMethod> findMethodImpls(PsiMethod intMethod, PsiClass containingClass) {
        return Stream.of(containingClass.getAllMethods()).filter(classMethod -> Arrays.asList(classMethod.findSuperMethods()).contains(intMethod)).collect(toList());
    }

    private void addComment(PsiJavaDocumentedElement from, PsiJavaDocumentedElement to) {
        Runnable r = () -> {
            PsiElement docComment = from.getDocComment().copy();
            final PsiElement child = to.getFirstChild();
            to.addBefore(docComment, child);
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void renameFile(PsiClass containingClass, String name) {
        Runnable r = () -> {
            try {
                containingClass.getContainingFile().getVirtualFile().rename("rename", name);
            } catch (IOException e) {
                log(e.getMessage());
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void moveFile(PsiClass containingClass, PsiDirectory parent) {
        Runnable r = () -> {
            try {
                containingClass.getContainingFile().getVirtualFile().move("move", parent.getVirtualFile());
            } catch (IOException e) {
                log(e.getMessage());
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void deleteFile(PsiClass anInterface) {
        Runnable r = () -> {
            try {
                anInterface.getContainingFile().getVirtualFile().delete("delete");
            } catch (IOException e) {
                log(e.getMessage());
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }


    public void log(String text) {
//        Messages.showMessageDialog(project, text, "Logger", null);
        System.err.println(text);
    }
}