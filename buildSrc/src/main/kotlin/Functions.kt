import java.io.File
import java.util.Properties
import org.gradle.api.Project;

private fun loadProps(props: File, ext: HashMap<String, String>) {
  if (props.exists()) {
    props.reader().use {
      Properties().apply {
        load(it)
      }
    }.onEach { (name, value) ->
      ext[name.toString()] = value.toString()
    }
  }
}

fun getPropString(project: Project, name: String): String {
  val ext = HashMap<String, String>()
  loadProps(project.rootProject.file("local.properties"), ext)
  loadProps(project.rootProject.file("gradle.properties"), ext)
  return ext[name] ?: ""
}
