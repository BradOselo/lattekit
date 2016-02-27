package io.lattekit.ui.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.lattekit.Latte
import io.lattekit.annotation.Prop
import io.lattekit.ui.LatteActivity
import java.lang.reflect.Field
import kotlin.reflect.KClass

/**
 * Created by maan on 2/15/16.
 */


open class LatteView {

    companion object {
        @JvmStatic
        fun log(message: String) {
            Log.d("Latte", message)
        }

        @JvmStatic
        fun log(tag: String, message: String) {
            Log.d(tag, message)
        }
    }

    var renderedViews: MutableList<LatteView> = mutableListOf()
    var newRenderedViews = mutableListOf<LatteView>()
    var androidView: View? = null

    var props: MutableMap<String, Any?> = mutableMapOf()
    var parentView: LatteView? = null

    var children = mutableListOf<LatteView>()

    var activity: Activity? = null
    var isMounted: Boolean = false;

    var dataValues = mutableMapOf<String, Any?>()

    var layoutFn : (LatteView.() -> Unit)? = null


    val propFields: MutableMap<String, Field>
        get() {
            var map = Latte.PROP_FILEDS[this.javaClass.name]
            if (map == null) {
                val newMap = mutableMapOf<String, Field>()
                var cls: Class<in Object> = this.javaClass
                while (cls != Object::class.java) {
                    cls.declaredFields.forEach { f ->
                        if (f.isAnnotationPresent(Prop::class.java)) {
                            var anno = f.getAnnotation(Prop::class.java);
                            var name: String = if (anno.value != "") anno.value else f.name;
                            f.setAccessible(true);
                            newMap.put(name, f)
                        }
                    }
                    cls = cls.superclass
                }
                Latte.PROP_FILEDS.put(this.javaClass.name, newMap)
                return newMap
            }
            return map!!
        }


    var rootAndroidView: View? = null
        get() {
            if (this.androidView != null) {
                return this.androidView
            } else if (this.renderedViews.get(0) != null) {
                return this.renderedViews.get(0).rootAndroidView;
            }
            return null
        }


    var id: Int = 0
        get() = this.props.get("id") as Int

    fun buildView(activity: Activity, lp: ViewGroup.LayoutParams?): View {
        this.activity = activity;
        this.renderTree()
        this.buildAndroidViewTree(activity, lp);
        return this.rootAndroidView!!;
    }

    fun notifyMounted() {
        isMounted = true;
        findRefs(this.renderedViews);
        Latte.PLUGINS.forEach { it.onViewMounted(this) }
        onViewMounted();
    }

    fun onStateChanged() {
        this.renderTree()
        this.buildAndroidViewTree(activity as Context, rootAndroidView?.layoutParams!!);
        Latte.PLUGINS.forEach { it.onViewRendered(this) }
    }

    fun data(key: String): Any? {
        return dataValues.get(key)
    }

    inline fun data(key: String, value: Any?): Any? {
        dataValues.put(key, value)
        return value
    }

    fun dataOrPut(key: String, defaultValue: Any?): Any? {
        var originalValue = dataValues.get(key)
        if (originalValue == null) {
            dataValues.put(key, defaultValue)
            return defaultValue
        }
        return originalValue
    }


    fun show(caller: LatteView) {
        var myId = "${System.currentTimeMillis()}";
        var intent = Intent(caller.rootAndroidView?.context, LatteActivity::class.java);
        intent.putExtra("_LATTE_KIT_OBJ_ID", myId)
        Latte.SAVED_OBJECTS.put(myId, this)
        caller.rootAndroidView?.context?.startActivity(intent);
    }

    open fun onViewMounted() {
    }

    open fun onViewWillMount() {

    }

    fun buildAndroidViewTree(a: Context, lp: ViewGroup.LayoutParams?): View {
        // First build my view
        this.activity = a as Activity;
        if (!isMounted) {
            Latte.PLUGINS.forEach { it.onViewWillMount(this) }
            this.onViewWillMount()
        }
        if (this is NativeView) {
            if (this.androidView == null) {
                this.androidView = this.renderNative(a);
            }
            if (this.androidView?.layoutParams == null) {
                this.androidView?.layoutParams = if (lp == null) {
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
                } else { lp; }
            }
            if (this is NativeViewGroup) {
                this.mountChildren()
            }
            if (!isMounted) {
                notifyMounted();
            }
            return this.androidView!!
        } else {
            // If we don't have native android view, then we are virtual node
            var subAndroid = this.renderedViews[0].buildAndroidViewTree(a, lp);
            if (!isMounted) {
                notifyMounted();
            }
            return subAndroid;
        }
    }

    fun copy(): LatteView {
        val copy = this.javaClass.newInstance()
        var copyProps = mutableMapOf<String,Any?>();
        this.props.forEach { entry -> copyProps.put(entry.key, entry.value) }
        copy.props = copyProps
        copy.children = mutableListOf()
        children.forEach { copy.children.add(it.copy()) }
        return copy;
    }

    fun findRefs(subViews: List<LatteView>) {
        subViews.forEach { it: LatteView ->
            var ref: String? = it.props.get("ref") as String?
            if ( ref != null ) {
                val fieldName = ref;
                var field = this.javaClass.getDeclaredFields().find { f -> f.name == fieldName }
                if (field != null) {
                    field.setAccessible(true);
                    if (field.getType().isAssignableFrom(it.javaClass)) {
                        field.set(this, it);
                    } else if (it.androidView != null && field.getType().isAssignableFrom(it.androidView?.javaClass)) {
                        field.set(this, it.androidView);
                    }
                } else {
                    log("Couldn't find field " + fieldName)
                }
            }
            this.findRefs(it.children)
        }
    }


    open fun injectProps() {
        log("${this} I have props fields ${propFields.map { "${it.key}" }.joinToString(",")} ::: ${this.javaClass.declaredFields.map { "${it.name} : ${it.isAnnotationPresent(Prop::class.java)}" }.joinToString(",")}")
        propFields.forEach { it ->
            if (!this.props.containsKey(it.key)) {
                // Remove deleted property
                var field = propFields.get(it.key)
                field?.set(this, null)
            }
        }

        this.props?.forEach { entry ->
            var field: Field? = this.propFields[entry.key]
            if ( field != null) {
                if (field.getType().isAssignableFrom(entry.value?.javaClass)) {
                    field.set(this, entry.value)
                } else {
                    // TODO: Maybe need to throw exception ?
                    log("WARNING: Provided property ${entry.key} value with different type, it will be set to null")
                }

            }
        }
    }


    fun sameView(leftView: LatteView, rightView: LatteView): Boolean {
        if (leftView.javaClass == rightView.javaClass) {
            return true;
        }
        return false;
    }

    inline fun register(clazz: KClass<*>,  crossinline init: LatteView.() -> Unit) : LatteView {
        var view = Latte.create(clazz.java,mutableMapOf(), ChildrenProc { it->
            it.init()
        })
        addChild(view);
        return view
    }

    fun addChild(child: LatteView) {
        children.add(child)
    }

    fun render(xml : String) {
    }

    fun render(newView : LatteView) {
        var i = newRenderedViews.size
        if (i < renderedViews.size) {
            var oldView: LatteView = renderedViews.get(i)
            if (sameView(oldView, newView)) {
                var oldProps = oldView.props
                oldView.children = newView.children
                oldView.props = newView.props
                Latte.PLUGINS.forEach { it.onPropsUpdated(oldView, oldView.props) }
                if (oldView.onPropsUpdated(oldProps)) {
                    oldView.renderTree()
                }
                newRenderedViews.add(oldView)
            } else {
                // Try find recycled view
                var recycledOldView = Latte.getRecycledView(newView)
                if (recycledOldView != null) {
                    recycledOldView.parentView = this
                    recycledOldView.children = newView.children
                    recycledOldView.props = newView.props
                    recycledOldView.isMounted = false
                    recycledOldView.renderTree()
                    newRenderedViews.add(recycledOldView)
                } else {
                    newView.parentView = this
                    newView.renderTree()
                    newRenderedViews.add(newView)
                }
            }
        } else {
            var recycledOldView = Latte.getRecycledView(newView)
            if (recycledOldView != null) {
                recycledOldView.parentView = this
                recycledOldView.children = newView.children
                recycledOldView.props = newView.props
                recycledOldView.isMounted = false
                recycledOldView.renderTree()
                newRenderedViews.add(recycledOldView)
            } else {
                newView.parentView = this
                newView.renderTree()
                newRenderedViews.add(newView)
            }
        }
    }

    fun prop(str : String, value : Any) {
        this.props.put(str,value)
    }

    open fun layout() : LatteView? {
        if (this.layoutFn != null) {
            this.children.clear()
            this.layoutFn!!()
            return this.children.get(0)
        }
//        throw Exception("Empty layout function ? Please make sure you override layout of ${this.javaClass.name} ")
        return null
    }

    fun renderTree() {
        newRenderedViews = mutableListOf()
        injectProps()
        var layoutView = this.layout();
        if (layoutView != null) {
            render(layoutView)
        }
        if (this is NativeViewGroup) {
            for (child in this.children) {
                render(child)
            }
        }

        this.renderedViews.forEach {
            if (!newRenderedViews.contains(it)) {
                Latte.recycleView(it)
            }
        }
        this.renderedViews = newRenderedViews;
    }


    open fun onPropsUpdated(props: Map<String, Any?>): Boolean {
        return true;
    }

}

