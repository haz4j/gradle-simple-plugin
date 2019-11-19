import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class HelloAction extends AnAction {

    private List<String> skipMethods = Arrays.asList("registerNatives", "Object", "getClass", "hashCode",
            "equals", "clone", "toString", "notify", "notifyAll", "wait", "wait", "wait", "finalize");

    Project project = null;

    public HelloAction() {
        super("Hello");
    }

    public void actionPerformed(AnActionEvent anActionEvent) {
        project = anActionEvent.getProject();

        Editor editor = anActionEvent.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = anActionEvent.getData(CommonDataKeys.PSI_FILE);

        if (editor == null || psiFile == null) return;
        int offset = editor.getCaretModel().getOffset();

        PsiElement element = psiFile.findElementAt(offset);
        if (element == null) {
            return;
        }

        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (containingMethod == null) {
            return;
        }

        PsiClass containingClass = containingMethod.getContainingClass();

        PsiClass[] interfaces = containingClass.getInterfaces();

        if (interfaces.length != 1) {
            log("More than 1 interface");
            return;
        }

        PsiClass anInterface = interfaces[0];

        PsiMethod previousClassMethod = null;

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
                //        deleteOverride(classMethod);
                previousClassMethod = classMethod;
            }

        }

        changePackage(containingClass, anInterface);

        String interfaceName = anInterface.getContainingFile().getName();
        PsiDirectory parent = anInterface.getContainingFile().getParent();

        deleteFile(anInterface);
        moveFile(containingClass, parent);
        renameFile(containingClass, interfaceName);
    }

    private void copyMethod(PsiMethod intMethod, PsiMethod previousClassMethod) {
        Runnable r = () -> {
            PsiElement newMethod = intMethod.copy();
            PsiElement[] children = newMethod.getChildren();

            for (PsiElement child : children) {
                PsiElement defaultModificator = child;
                log(defaultModificator.getText());
                if (defaultModificator.getText().equals("default")) {
//                    defaultModificator.delete();
                }
            }

            final PsiElement variableParent = previousClassMethod.getParent();

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

    private void deleteOverride(PsiMethod classMethod) {
        Runnable r = () -> {
            log(classMethod.getChildren());
            Optional<PsiElement> override = Stream.of(classMethod.getChildren()).filter(childElem -> childElem.getText().contains("@Override")).findAny();
            override.ifPresent(psiElement -> {
                log("delete");
                psiElement.delete();
//            classMethod.deleteChildRange(psiElement, psiElement);
            });
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void log(PsiElement[] elems) {
        Stream.of(elems).forEach(c -> log(c.getText()));
    }

    private void addComment(PsiMethod intMethod, PsiMethod classMethod) {
        Runnable r = () -> {
            PsiElement docComment = intMethod.getDocComment().copy();
            final PsiElement variableParent = classMethod.getFirstChild();
            classMethod.addBefore(docComment, variableParent);
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