package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.message.MessageDispatchManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.operametrix.ignition.git.GatewayHook;
import com.operametrix.ignition.git.records.GitAutomationRecord;
import org.python.core.Py;
import org.python.core.PyDictionary;
import org.python.core.PyList;
import org.python.core.PyObject;
import org.python.core.PyStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Delivers a {@link GitEvent} into Jython — a project library function, a Gateway Event message
 * handler, or both.
 *
 * <p>Runs on the event bus worker thread, never on the thread that performed the git operation.
 */
final class ScriptDelivery {

    private static final Logger logger = LoggerFactory.getLogger(ScriptDelivery.class);

    private ScriptDelivery() {
    }

    static List<String> deliver(GitEvent event, GitAutomationRecord cfg) {
        List<String> notes = new ArrayList<>();
        if (!cfg.hasScriptTarget() && !cfg.hasMessageTarget()) {
            notes.add("no script target");
            return notes;
        }

        PyDictionary payload = toPyDict(event);

        if (cfg.hasScriptTarget()) {
            notes.add(runFunction(cfg.getHandlerProject(), cfg.getHandlerScript(), payload));
        }
        if (cfg.hasMessageTarget()) {
            notes.add(dispatchMessage(cfg.getMessageProject(), cfg.getMessageHandler(), payload));
        }
        return notes;
    }

    /**
     * Calls a dotted project-library path, e.g. {@code Git.Events.onGitEvent}.
     *
     * <p>Tried twice on purpose: an explicit {@code import} of the containing module is what a
     * hand-written gateway script would do, but a project script manager also seeds the library
     * into its own namespace, and which of the two resolves depends on how the library is
     * packaged. Falling back to the bare call means a working configuration is not rejected
     * over an ImportError that does not matter.
     */
    private static String runFunction(String project, String path, PyDictionary payload) {
        GatewayContext ctx = GatewayHook.getContext();
        if (ctx == null) {
            return "script skipped (no context)";
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot <= 0) {
            return "script failed (" + path + " is not a Package.module.function path)";
        }
        String module = path.substring(0, lastDot);

        try {
            ScriptManager sm = ctx.getProjectManager().getProjectScriptManager(project);
            if (sm == null) {
                return "script failed (no such project: " + project + ")";
            }
            try {
                PyStringMap locals = sm.createLocalsMap();
                locals.__setitem__("gitEvent", payload);
                sm.runCode("import " + module + "\n" + path + "(gitEvent)\n", locals, "git-automation");
            } catch (Exception importPathFailed) {
                PyStringMap locals = sm.createLocalsMap();
                locals.__setitem__("gitEvent", payload);
                sm.runCode(path + "(gitEvent)\n", locals, "git-automation");
            }
            return "script ok";
        } catch (Exception e) {
            logger.warn("Git automation script {}.{} failed.", project, path, e);
            return "script failed (" + GitEvents.reason(e) + ")";
        }
    }

    private static String dispatchMessage(String project, String handler, PyDictionary payload) {
        GatewayContext ctx = GatewayHook.getContext();
        if (ctx == null) {
            return "message skipped (no context)";
        }
        try {
            Properties props = new Properties();
            props.put(MessageDispatchManager.KEY_SCOPE, MessageDispatchManager.SCOPE_GATEWAY_ONLY);
            List<String> result =
                    ctx.getMessageDispatchManager().dispatch(project, handler, payload, props);
            int sent = result == null ? 0 : result.size();
            return "message ok (" + sent + ")";
        } catch (Exception e) {
            logger.warn("Git automation message handler {}.{} failed.", project, handler, e);
            return "message failed (" + GitEvents.reason(e) + ")";
        }
    }

    /** The innermost cause's message — an Ignition/Jython wrapper's own text says nothing useful. */

    private static PyDictionary toPyDict(GitEvent event) {
        PyDictionary dict = new PyDictionary();
        for (Map.Entry<String, Object> entry : event.toMap().entrySet()) {
            dict.__setitem__(py(entry.getKey()), toPy(entry.getValue()));
        }
        return dict;
    }

    private static PyObject toPy(Object value) {
        if (value instanceof List<?> list) {
            PyList out = new PyList();
            for (Object item : list) {
                out.append(py(String.valueOf(item)));
            }
            return out;
        }
        return py(String.valueOf(value));
    }

    /**
     * A Jython string that tolerates non-ASCII.
     *
     * <p>{@code Py.newString} throws on any character above 0xFF — so one accented name in a
     * commit message, or a curly quote pasted from a document, killed delivery of the whole
     * event, script and outbound trigger alike. Measured, not theorised: an em dash in a
     * gateway-generated message did exactly that.
     */
    private static PyObject py(String value) {
        return Py.newStringOrUnicode(value);
    }
}
