package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.parameterInfo.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;

import java.awt.Color;
import java.util.List;

/** Parameter info snapshots are prepared during resolution and rendered without resolving on the EDT. */
public class JypeParameterInfoTest extends JypeJavaFixture {
    public void testFindShowUpdateAndRender() {
        objectSystem("api", "ENTITY");
        myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public void accept(ENTITY value, int count) {}
                }
                """);
        PsiFile file = myFixture.configureByText("Consumer.java", """
                package api;
                class Consumer { Service service; void use() { service.accept(null, <caret>); } }
                """);
        Context context = new Context(file, myFixture.getEditor(), myFixture.getCaretOffset());
        JypeParameterInfoHandler handler = new JypeParameterInfoHandler();
        PsiExpressionList owner = handler.findElementForParameterInfo(context);
        assertNotNull(owner);
        assertEquals(1, context.items.length);
        handler.showParameterInfo(owner, context);
        assertSame(owner, context.owner);
        assertSame(handler, context.handler);
        handler.updateParameterInfo(owner, context);
        assertEquals(1, context.current);
        handler.updateUI((JypeParameterInfoHandler.Candidate) context.items[0], context);
        assertEquals("ENTITY value, int count", context.text);
        assertEquals(14, context.start);
        assertEquals(context.text.length(), context.end);
        context.owner = null;
        handler.updateParameterInfo(owner, context);
        assertTrue(context.removed);
        context.offset = file.getText().indexOf("class");
        assertNull(handler.findElementForUpdatingParameterInfo(context));
        assertNull(handler.findElementForParameterInfo(context));
    }

    public void testVarargsDeprecationAndOutOfRangePresentation() {
        PsiMethod method = myFixture.addClass("class Methods { void method() {} }").getMethods()[0];
        var candidate = new JypeParameterInfoHandler.Candidate(method, List.of("ENTITY... entities"), true, true);
        Context context = new Context(method.getContainingFile(), myFixture.getEditor(), 0);
        context.current = 5;
        context.enabled = false;
        new JypeParameterInfoHandler().updateUI(candidate, context);
        assertEquals(0, context.start);
        assertEquals(context.text.length(), context.end);
        assertTrue(context.disabled);
        assertTrue(context.strikeout);
        context.current = -1;
        new JypeParameterInfoHandler().updateUI(candidate, context);
        assertEquals(-1, context.start);
        candidate = new JypeParameterInfoHandler.Candidate(method, List.of(), false, true);
        context.current = 0;
        new JypeParameterInfoHandler().updateUI(candidate, context);
        assertEquals("", context.text);
        assertEquals(-1, context.start);
    }

    public void testConstructorAndOrdinaryCallsAndIndexing() {
        objectSystem("api", "ENTITY");
        myFixture.addClass("package api; public class Service<ENTITY> { public Service(ENTITY value) {} }");
        PsiFile file = myFixture.configureByText("Consumer.java",
                "package api; class Consumer { Object value = new Service(<caret>null); }");
        Context context = new Context(file, myFixture.getEditor(), myFixture.getCaretOffset());
        var handler = new JypeParameterInfoHandler();
        assertNotNull(handler.findElementForParameterInfo(context));
        assertEquals(1, context.items.length);
        assertEquals(List.of("ENTITY value"), ((JypeParameterInfoHandler.Candidate) context.items[0]).parameters());
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertNull(handler.findElementForParameterInfo(context)));
        file = myFixture.configureByText("Ordinary.java", "class Ordinary { void method() { System.out.println(<caret>); } }");
        assertNull(handler.findElementForParameterInfo(new Context(file, myFixture.getEditor(), myFixture.getCaretOffset())));
        PsiClass enumeration = myFixture.addClass("enum Values { VALUE(1); Values(int value) {} }");
        PsiExpressionList synthetic = ((PsiEnumConstant) enumeration.getFields()[0]).getArgumentList();
        assertEmpty(JypeParameterInfoHandler.candidates(synthetic));
        file = myFixture.configureByText("Unknown.java", "class Unknown { void method() { missing(<caret>); } }");
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        assertEmpty(JypeParameterInfoHandler.candidates(call.getArgumentList()));
    }

    private static final class Context extends UserDataHolderBase
            implements CreateParameterInfoContext, UpdateParameterInfoContext, ParameterInfoUIContext {
        private final PsiFile file;
        private final Editor editor;
        private int offset;
        private int current;
        private Object[] items;
        private PsiElement owner;
        private Object highlighted;
        private ParameterInfoHandler<?, ?> handler;
        private boolean removed;
        private boolean enabled = true;
        private String text;
        private int start;
        private int end;
        private boolean disabled;
        private boolean strikeout;

        private Context(PsiFile file, Editor editor, int offset) {
            this.file = file;
            this.editor = editor;
            this.offset = offset;
        }

        @Override public Project getProject() { return file.getProject(); }
        @Override public PsiFile getFile() { return file; }
        @Override public int getOffset() { return offset; }
        @Override public Editor getEditor() { return editor; }
        @Override public Object[] getItemsToShow() { return items; }
        @Override public void setItemsToShow(Object[] items) { this.items = items; }
        @Override public void showHint(PsiElement element, int offset, ParameterInfoHandler handler) { owner = element; this.handler = handler; }
        @Override public int getParameterListStart() { return 0; }
        @Override public PsiElement getHighlightedElement() { return highlighted instanceof PsiElement psi ? psi : null; }
        @Override public void setHighlightedElement(PsiElement element) { highlighted = element; }
        @Override public void removeHint() { removed = true; }
        @Override public void setParameterOwner(PsiElement owner) { this.owner = owner; }
        @Override public PsiElement getParameterOwner() { return owner; }
        @Override public void setHighlightedParameter(Object parameter) { highlighted = parameter; }
        @Override public Object getHighlightedParameter() { return highlighted; }
        @Override public void setCurrentParameter(int index) { current = index; }
        @Override public boolean isUIComponentEnabled(int index) { return enabled; }
        @Override public void setUIComponentEnabled(int index, boolean enabled) { this.enabled = enabled; }
        @Override public Object[] getObjectsToView() { return items; }
        @Override public boolean isPreservedOnHintHidden() { return false; }
        @Override public void setPreservedOnHintHidden(boolean value) {}
        @Override public boolean isInnermostContext() { return true; }
        @Override public boolean isSingleParameterInfo() { return false; }
        @Override public UserDataHolderEx getCustomContext() { return this; }
        @Override public String setupUIComponentPresentation(String text, int start, int end, boolean disabled,
                                                            boolean strikeout, boolean before, Color background) {
            this.text = text; this.start = start; this.end = end; this.disabled = disabled; this.strikeout = strikeout;
            return text;
        }
        @Override public void setupRawUIComponentPresentation(String text) { this.text = text; }
        @Override public boolean isUIComponentEnabled() { return enabled; }
        @Override public void setUIComponentEnabled(boolean enabled) { this.enabled = enabled; }
        @Override public int getCurrentParameterIndex() { return current; }
        @Override public boolean isSingleOverload() { return true; }
        @Override public Color getDefaultParameterColor() { return Color.WHITE; }
    }
}
