import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

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

        for (PsiMethod intMethod : anInterface.getAllMethods()) {

            if (skipMethods.contains(intMethod.getName())) {
                continue;
            }

            PsiDocComment docComment = intMethod.getDocComment();
            if (docComment != null) {
                log("method - " + intMethod.toString());
                log("docComment - " + docComment.getText());
            }
            PsiMethod[] methodsInClass = containingClass.findMethodsByName(intMethod.getName(), false);
            if (methodsInClass.length != 1) {
                log("more than 1 imp of method - " + intMethod.getName());
            } else {
                PsiMethod methodInClass = methodsInClass[0];

            }
        }
        String interfaceName = anInterface.getContainingFile().getName();
        PsiDirectory parent = anInterface.getContainingFile().getParent();

        deleteFile(anInterface);
        moveFile(containingClass, parent);
        renameFile(containingClass, interfaceName);
    }

    private void renameFile(PsiClass containingClass, String name) {
        Runnable r = () -> {
            try {
                containingClass.getContainingFile().getVirtualFile().rename("rename", name);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void moveFile(PsiClass containingClass, PsiDirectory parent) {
        Runnable r = () -> {
            try {
                containingClass.getContainingFile().getVirtualFile().move("move", parent.getVirtualFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }

    private void deleteFile(PsiClass anInterface) {
        Runnable r = () -> {
            try {
                anInterface.getContainingFile().getVirtualFile().delete("delete");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        WriteCommandAction.runWriteCommandAction(project, r);
    }


    public void log(String text) {
        Messages.showMessageDialog(project, text, "Logger", null);
    }
}