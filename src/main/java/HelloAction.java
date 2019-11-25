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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class HelloAction extends AnAction {

    private List<String> skipMethods = Arrays.asList("registerNatives", "Object", "getClass", "hashCode",
            "equals", "clone", "toString", "notify", "notifyAll", "wait", "wait", "wait", "finalize");

    private Project project = null;
    private PsiClass containingClass;

    public HelloAction() {
        super("Hello");
    }

    public void actionPerformed(AnActionEvent anActionEvent) {

        project = anActionEvent.getProject();
        VirtualFile file = anActionEvent.getData(CommonDataKeys.VIRTUAL_FILE);
        List<PsiFile> psiFiles = null;

        if (file.isDirectory()) {
            try {
                List<Path> collect = Files.walk(Paths.get(file.getPath()))
                        .filter(s -> s.toString().endsWith("Impl.java"))
                        .collect(toList());
                psiFiles = collect.stream().map(col -> {
                    File file1 = new File(col.toUri());
                    VirtualFile fileByIoFile = LocalFileSystem.getInstance().findFileByIoFile(file1);
                    PsiFile file2 = PsiManager.getInstance(project).findFile(fileByIoFile);
                    return file2;
                }).collect(toList());


            } catch (IOException e) {
                log(e.getMessage());
            }
        } else {
            PsiFile psiFile = anActionEvent.getData(CommonDataKeys.PSI_FILE);
            if (psiFile == null) return;
            psiFiles = Arrays.asList(psiFile);
        }
        psiFiles.forEach(this::process);

    }

    private void process(PsiFile psiFile) {
        psiFile.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                super.visitClass(aClass);
                containingClass = aClass;
            }
        });

        PsiClass[] interfaces = containingClass.getInterfaces();

        if (interfaces.length != 1) {
            log("More than 1 interface");
            return;
        }

        PsiClass anInterface = interfaces[0];

        PsiMethod previousClassMethod = null;

        deteteInheritDoc(containingClass);

        if (anInterface.getDocComment() != null) {
            addComment(anInterface, containingClass);
        }

        for (PsiMethod intMethod : anInterface.getAllMethods()) {

            if (skipMethods.contains(intMethod.getName())) {
                continue;
            }

            List<PsiMethod> classMethods = findMethodImpls(intMethod, containingClass);

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

        for (PsiMethod classMethod : containingClass.getAllMethods()) {
            if (skipMethods.contains(classMethod.getName())) {
                continue;
            }
        }

        changePackage(containingClass, anInterface);

        Path path = Paths.get(anInterface.getContainingFile().getVirtualFile().getPath());

        deleteFile(anInterface);
        deleteFile(containingClass);

        project.save();
        FileDocumentManager.getInstance().saveAllDocuments();
        ProjectManagerEx.getInstanceEx().blockReloadingProjectOnExternalChanges();


        String classText = containingClass.getContainingFile().getText();
        String[] split = classText.split("\\n");
        List<String> lines = Stream.of(split)
                .filter(line -> !line.contains("@Override"))
                .map(line -> line
                        .replaceAll("^(\\s*?)default\\s(.*)\\(", "$1public $2(")
                        .replaceAll("public class (.*)Impl implements (.*)", "public class $1 {")
                )
                .collect(toList());

//        PsiFileFactory.getInstance(project).createFileFromText(lines.stream().collect(Collectors.joining()), )

        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log(e.getMessage());
        }

        try {
            Thread.sleep(500L);
            SaveAndSyncHandler.getInstance().refreshOpenFiles();
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
            ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void deteteInheritDoc(PsiClass clazz) {
        Runnable r = () -> {
            deleteLastChildContainingText(clazz);
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

    private void log(PsiElement[] elems) {
        Stream.of(elems).forEach(c -> log(c.getText()));
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