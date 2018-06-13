# AppRunner

This application is designed to facilitate the task of grouping and performing a set of typical operations upon files, such as move, copy, delete and rename. It can also run external processes (hence the name "AppRunner"). You can describe its functionality as a small subset of operations from shell script. The configuration is performed through editing &lt;module&gt;.xml file and &lt;launch-specific&gt;.properties file. One should run AppRunner against a module and at least one task specified (later on about tasks). The main idea is that user doesn't have to be an expert in shell script, batch or any programming language to work with this tool. Instead it has a declarative XML style, which is closer to human language. Therefore it should be relatively easy to adjust a ready-to-use solution such as the main example *config/examples/tekken7-module.xml (more about it further in this readme)* to add new features following the existing pattern, or even create the new module from scratch to serve a completely different purpose.

## Getting Started

Use -h (--help) option to see usage.

Typical launch will look like this:

```
apprunner.exe -m my_module.xml -p common.properties -t task1 task2 ... taskN
```

### AppRunner directory structure

```
apprunner/
├── apprunner.exe
├── apprunner_log.txt
└── config/
    ├── apprunner-module.xsd
    ├── <your-module>.xml
    └── <your-properties>.properties
```

### &lt;your-module&gt;.xml

Module configuration file should refer to *apprunner-module.xsd* file in the following fashion:

```
<apprunner-module xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:noNamespaceSchemaLocation="apprunner-module.xsd">
```
where **&lt;apprunner-module&gt;** is the root element of the document and **"apprunner-module.xsd"** is a relative path to the XSD document stored on a local drive. AppRunner runs an additional validation of a XML file given as a *-m* argument against this XSD file. User must specify exactly one module file as a command line argument. However, this doesn't undermine the general capabilities of utilizing more than one module during a single run due to the mechanism of inheritance. Include a mention of a parent module in the form:

```
<inherits>
  <module>my-parent-module1.xml</module>
  <module>examples/my-parent-module2.xml</module>
  ...
</inherits>
```
AppRunner will search for *config/my-parent-module1.xml* and *config/examples/my-parent-module2.xml*.

*Note: circular dependence is considered to be an error, and the program will notify user about this fact by failing fast with an appropriate exception being thrown.*

Direct children of the root element are **&lt;configuration&gt;** and **&lt;tasks&gt;** elements.

The first element **&lt;configuration&gt;** might have one **&lt;constants&gt;** section to define global scope constants (*i.e. (key,value) pairs*) accessible anywhere including tasks. User refers to a constant *homedir* declared as *&lt;constant name="homedir" value="c:\users\Johndoe" /&gt;* as follows: *&lt;files in="${homedir}" ends-with=".txt" /&gt;*. Also there might be one **&lt;actions&gt;** section, where user defines *operations* to later refer to inside the tasks as follows: *&lt;operation ref="remove-files-inside-trash" /&gt;*.

The second element **&lt;tasks&gt;** lists the actual jobs of this module. User can select any tasks from this list to execute with *-t* key followed by task names. Any task might declare its own constants within **&lt;constants&gt;** child element of the corresponding parent element **&lt;task&gt;**. They will be visible only within this task.

Each task element has to include exactly one **&lt;actions&gt;** element, which in its turn is a parent node for nodes **&lt;application&gt;** and **&lt;operation&gt;** that may go in any order and in any number. This way user is allowed to declare a complicated task with a bloated **&lt;actions&gt;** section, that does a lot of things in a bulk, or he can choose to reduce the granularity by splitting the work across a larger number of more lightweight tasks.

**&lt;application&gt;** element represents an external process. Each **&lt;execute&gt;** entry corresponds to one execution of this process. User defines command line arguments within **&lt;execute&gt;**. E.g. this first execute element:

```
<application executable="${path_to_git}\git.exe">
  <execute>
    <cli-key value="commit" />
    <cli-key value="-c" />
    <cli-value value="ORIG_HEAD" />
  </execute>
  ...
</application>
```
will tell AppRunner to run *c:\git\git.exe commit -c "ORIG_HEAD"*.

**&lt;operation&gt;** represents a set of actions upon files such as move, copy, delete and rename. User can declare operations within **&lt;actions&gt;** block of a single task, or within **&lt;actions&gt;** block of **&lt;configuration&gt;** element. The latter allows user to later reuse this operation in more than one task. Say user declared an operation like this:

```
<configuration>
  ...
  <actions>
    <operation name="move-harry-potter-and-rons-family-to-hogwarts">
       <move to="${hogwarts}">
         <file in="${london}" starts-with="Harry Potter" />
         <files in="${burrow}" contains="Weasley" />
       </move>
    </operation>
    ...
```
Then he can write in any task:

```
<operation ref="move-harry-potter-and-rons-family-to-hogwarts" />
```

### &lt;common&gt;.properties

The format of *.properties* file adheres to a simple per line *key = value* entry structure. Each entry represents a constant of the global scope (*i.e.* **&lt;configuration&gt;** *level constant*). Also in case a module has its own definition of any constant appearing in properties file, values read from properties file take the highest priority. E.g. given the following definition inside a module of the constant:

    <constant name="myconst" value="some_value" />,
    
Reading from properties:

    myconst = ${person} dislikes ${a}|${person} dislikes ${b}
    
will assign *${person} dislikes ${a}|${person} dislikes ${b}* to *myconst* as the final value, while *some_value* will no longer be taken in account in any shape or form, when it comes to dereferencing of *${myconst}* reference.

This example also reveals the powerful mechanism of names referencing, which is extensively used within *module.xml* syntax. Any constant referenced within the tasks supplied as command line parameters has to be successfully resolved by AppRunner to a simple string, containing no references to constants. Also the pipe "|" symbol used within the value section of constant's declaration denotes *options*. The order of options sets a natural priority upon them. Therefore the first option from left to right, which can be successfully reduced to a simple string without references, will eventually become the final value of this constant. In case every single option contains at least one reference to a name, that can not be completely dereferenced, the program fails fast before any task has been executed. E.g. having *person* and *b* defined and *a* undefined will make *myconst* taking the value of the second option. I.e. if *a* can not be dereferenced and user set:

```
myconst = ${person} dislikes ${a}|${person} dislikes ${b}
person = John Doe
b = meat
```
then mr.Doe becomes a vegan.

## Tekken 7 modding example

A large amount of routine work a modder has to repeat for every new mod served the main inspiration for writing this application. Hence *config/examples/tekken7-module.xml* along with *common.properties* is the main example, demonstrating the advantages of using this tool.

*config/examples/tekken7-module.xml's* tasks:

- **cleanup**: removes temporary directory *${mod_name}* in *./mods_unpacked*
- **material_instance**: runs *uassetrenamer.exe* against *.uasset* files specified in *common.properties* located in *TekkenGame\Content\Character\Common\shader\MaterialInstance\skin\\${character}*.

character_item:

- **character_item_lower**: -||- in *TekkenGame\Content\Character\Item\CharacterItem\\${character}\LOWER*
- **character_item_upper**: *...\UPPER*
- **character_item_hair**: *...\HAIR*
- **character_item_full_body**: *...\FULL_BODY*

customize:

- **customize_lower**: -||- in *TekkenGame\Content\Character\Item\Customize\\${character}\LOWER*
- **customize_upper**: *...\UPPER*
- **customize_hair**: *...\HAIR*
- **customize_full_body**: *...\FULL_BODY*

replace_images:

- **replace_images_cus_item_lower**: -||- in *TekkenGame\Content\UI_common\Texture2D\ReplaceImages\CUS_ITEM\\${character}*
- **replace_images_cus_item_upper**: -||-
- **replace_images_cus_item_hair**: -||-
- **replace_images_cus_item_full_body**: -||-

Other:

- **pack_mod**: runs *u4pak.exe* against *TekkenGame* folder with *.uasset* files created by running previous tasks
- **move_new_mod_to_~mods**: moves *${mod_name}.pak* to ~mods folder inside the Tekken 7 game directory
- **delete_tmp_module_dir_with_uasset_files**: removes a temporary *TekkenGame* folder, created by running the task **pack_mod**
- **copy_properties_to_~mods_and_rename_to_mod_name**: copies *common.properties* file user used to create this mod to *~mods* and renames it to *${mod_name}.properties*

Note:
- There is no such constant as ${character}. It has been written this way here only for the sake of brevity. Constants ${character_to} and ${character_from} are used instead. 
- Make sure you have directories *mods_packed* and *mods_unpacked* to store temporary files.

Constants inside *config/examples/tekken7-module.xml* to tweak:

- **quickbms_t7_out_dir**: you should specify the directory with *.uasset* files of Tekken 7 as a result of running quickbms against pak archives
- **uasset_renamer_dir**: directory with *uassetrenamer.exe*
- **u4pak_dir**: directory with *u4pak.exe*
- **t7_~mods_dir**: path to ~mods directory of installed Tekken 7 on your computer

###Examples

Let's create a batch file, that runs *AppRunner* with the command to create a simple mod, where one upper part and one lower part of one character gets replaced by the corresponding upper and lower parts of another character. The batch file *make_simple_mod.bat* will be as follows:

```
apprunner.exe -m examples\t7_sound_module.xml -p examples\common.properties -t cleanup material_instance character_item_lower customize_lower replace_images_cus_item_lower character_item_upper customize_upper replace_images_cus_item_upper pack_mod move_new_mod_to_~mods delete_tmp_module_dir_with_uasset_files copy_properties_to_~mods_and_rename_to_mod_name
```
where *common.properties* is a properties file, where user adjusts values of constants for each new mod before running the batch file. AppRunner will start with an execution of *cleanup* task to delete any temporary directory named as the specified *mod_name* constant in **./mods_unpacked** (in case there was one from previous attempts of creating this mod). It will continue with executing the remaining tasks in the order they appear in command line. Lastly AppRunner will copy *common.properties* to *~mods* inside *Tekken 7* installation directory and rename it as *<mod_name>.properties*.

*Note: in Unix-like OS you should write ./apprunner.exe instead of apprunner.exe*

If user wanted to replace Lili's "Armored Pants" with Eliza's "1P Pants" and Lili's "T-Shirt (Flower)" with Eliza's "1P Big Top", then the *common.properties* file will have these lines:

```
mod_name = Lili_As_Eliza_1p_big_18_18
character_to = LIL
character_from = ELZ
costume_lower_to = military_pts_f
costume_lower_from = 1P_CUS
costume_upper_to = T_GARA_A_F
costume_upper_from = 1p_big
```
In case of FileNotFoundException a list of possible file matches will be prompted.

## Prerequisites

The program requires JRE (Java Runtime Environment) version 1.8 or later to run. However in case of absence of JRE, it will be prompted to be installed.

Note: if you want to get your hands on this project as a dev, there is little if anything specific to know, since it's a Maven project. Make sure the project settings use 1.8 or later Java environment.
* [Maven](https://maven.apache.org/) - Dependency Management

## Authors

* **Aleksandr Ivanov** - *Initial work* - [vitrums](https://github.com/vitrums)