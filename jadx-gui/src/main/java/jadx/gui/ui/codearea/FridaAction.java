package jadx.gui.ui.codearea;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;
import jadx.api.JavaMethod;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.metadata.annotations.VarNode;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JField;
import jadx.gui.treemodel.JMethod;
import jadx.gui.treemodel.JNode;
import jadx.gui.ui.action.ActionModel;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;

public final class FridaAction extends JNodeAction {
	private static final Logger LOG = LoggerFactory.getLogger(FridaAction.class);
	private static final long serialVersionUID = -3084073927621269039L;

	public FridaAction(CodeArea codeArea) {
		super(ActionModel.FRIDA_COPY, codeArea);
	}

	@Override
	public void runAction(JNode node) {
		try {
			String fridaSnippet = generateFridaSnippet(node);
			LOG.info("Frida snippet:\n{}", fridaSnippet);
			UiUtils.copyToClipboard(fridaSnippet);
		} catch (Exception e) {
			LOG.error("Failed to generate Frida code snippet", e);
			JOptionPane.showMessageDialog(getCodeArea().getMainWindow(), e.getLocalizedMessage(), NLS.str("error_dialog.title"),
					JOptionPane.ERROR_MESSAGE);
		}
	}

	@Override
	public boolean isActionEnabled(JNode node) {
		return node instanceof JMethod || node instanceof JClass || node instanceof JField;
	}

	private String generateFridaSnippet(JNode node) {
		if (node instanceof JMethod) {
			return generateMethodSnippet((JMethod) node, false);
		}
		if (node instanceof JClass) {
			return generateClassSnippet((JClass) node, true);
		}
		if (node instanceof JField) {
			return generateFieldSnippet((JField) node, false);
		}
		throw new JadxRuntimeException("Unsupported node type: " + (node != null ? node.getClass() : "null"));
	}

	private String generateMethodSnippet(JMethod jMth, boolean isAll) {
		MethodNode mth = jMth.getJavaMethod().getMethodNode();
		MethodInfo methodInfo = mth.getMethodInfo();
		String methodName;
		String newMethodName;
		if (methodInfo.isConstructor()) {
			methodName = "$init";
			newMethodName = methodName;
		} else {
			methodName = StringEscapeUtils.escapeEcmaScript(methodInfo.getName());
			newMethodName = StringEscapeUtils.escapeEcmaScript(methodInfo.getAlias());
		}
		String overload;
		if (isOverloaded(mth)) {
			String overloadArgs = methodInfo.getArgumentsTypes().stream()
					.map(this::parseArgType).collect(Collectors.joining(", "));
			overload = ".overload(" + overloadArgs + ")";
		} else {
			overload = "";
		}
		List<String> argNames = mth.collectArgNodes().stream()
				.map(VarNode::getName).collect(Collectors.toList());
		String args = String.join(", ", argNames);
		String logArgs;
		if (argNames.isEmpty()) {
			logArgs = "";
		} else {
			logArgs = ": " + argNames.stream().map(arg -> arg + "=${" + arg + "}").collect(Collectors.joining(", "));
		}
		String shortClassName = mth.getParentClass().getAlias();
		String classSnippet;
		if (isAll) {
			classSnippet = "";
		} else {
			classSnippet = generateClassSnippet(jMth.getJParent());
		}
		if (methodInfo.isConstructor() || methodInfo.getReturnType() == ArgType.VOID) {
			// no return value
			return classSnippet + "\n"
					+ shortClassName + "[\"" + methodName + "\"]" + overload + ".implementation = function (" + args + ") {\n"
					+ "    console.log(`start [Method] " + shortClassName + "." + newMethodName + " is called" + logArgs + "`);\n"
					+ "    this[\"" + methodName + "\"](" + args + ");\n"
					+ "    console.log(`end   [Method] " + shortClassName + "." + newMethodName + " result=void`);\n"
					+ "};";
		}
		return classSnippet + "\n"
				+ shortClassName + "[\"" + methodName + "\"]" + overload + ".implementation = function (" + args + ") {\n"
				+ "    console.log(`start [Method] " + shortClassName + "." + newMethodName + " is called" + logArgs + "`);\n"
				+ "    let result = this[\"" + methodName + "\"](" + args + ");\n"
				+ "    console.log(`end   [Method] " + shortClassName + "." + newMethodName + " result=${result}`);\n"
				+ "    return result;\n"
				+ "};";
	}

	private String generateClassSnippet(JClass jc) {
		JavaClass javaClass = jc.getCls();
		String rawClassName = StringEscapeUtils.escapeEcmaScript(javaClass.getRawName());
		String shortClassName = javaClass.getName();
		return String.format("let %s = Java.use(\"%s\");", shortClassName, rawClassName);
	}

	private String generateClassSnippet(JClass jc, boolean isAll) {
		JavaClass javaClass = jc.getCls();
		StringBuilder classSnippet = new StringBuilder(generateClassSnippet(jc));
		for (JavaField field : javaClass.getFields()) {
			classSnippet.append("\n").append(generateFieldSnippet(new JField(field, jc), true));
		}
		for (JavaMethod method : javaClass.getMethods()) {
			if (method.getName().equals("<clinit>")) {
				continue;
			}
			classSnippet.append("\n").append(generateMethodSnippet(new JMethod(method, jc), true));
		}
		return classSnippet.toString();
	}

	private String generateFieldSnippet(JField jf, boolean isAll) {
		JavaField javaField = jf.getJavaField();
		String rawFieldName = StringEscapeUtils.escapeEcmaScript(javaField.getRawName());
		String fieldName = javaField.getName();

		List<MethodNode> methodNodes = javaField.getFieldNode().getParentClass().getMethods();
		for (MethodNode methodNode : methodNodes) {
			if (methodNode.getName().equals(rawFieldName)) {
				rawFieldName = "_" + rawFieldName;
				break;
			}
		}
		JClass jc = jf.getRootClass();
		String classSnippet;
		if (isAll) {
			classSnippet = "";
		} else {
			classSnippet = generateClassSnippet(jc);
		}
		String printLog = String.format("console.log(` [Field] %s.%s.value-> ${%s}`);\n", jc.getName(), rawFieldName, fieldName);
		return String.format("%s\nlet %s = %s.%s.value;\n%s", classSnippet, fieldName, jc.getName(), rawFieldName, printLog);
	}

	public Boolean isOverloaded(MethodNode methodNode) {
		return methodNode.getParentClass().getMethods().stream()
				.anyMatch(m -> m.getName().equals(methodNode.getName())
						&& !Objects.equals(methodNode.getMethodInfo().getShortId(), m.getMethodInfo().getShortId()));
	}

	private String parseArgType(ArgType x) {
		String typeStr;
		if (x.isArray()) {
			typeStr = TypeGen.signature(x).replace("/", ".");
		} else {
			typeStr = x.toString();
		}
		return "'" + typeStr + "'";
	}
}
