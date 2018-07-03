package cc.meetpasser;

import com.google.gson.Gson;
import com.qiniu.common.QiniuException;
import com.qiniu.common.Zone;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import com.qiniu.util.StringUtils;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.*;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public class QiniuPublisher extends Recorder {

    private final List<QiniuEntry> entries = new ArrayList<QiniuEntry>();

    public QiniuPublisher() {
        super();
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
                           BuildListener listener) throws IOException, InterruptedException {
        // This is where you 'build' the project.
        // Since this is a dummy, we just say 'hello world' and call that a
        // build.

        // This also shows how you can consult the global configuration of the
        // builder
        FilePath ws = build.getWorkspace();
        String wsPath = ws.getRemote() + File.separator;
        PrintStream logger = listener.getLogger();
        Map<String, String> envVars = build.getEnvironment(listener);
        final boolean buildFailed = build.getResult() == Result.FAILURE;

        logger.println("开始上传到七牛...");
        for (QiniuEntry entry : this.entries) {

            if (entry.noUploadOnFailure && buildFailed) {
                logger.println("构建失败,跳过上传");
                continue;
            }

            QiniuProfile profile = this.getDescriptor().getProfileByName(
                    entry.profileName);
            if (profile == null) {
                logger.println("找不到配置项,跳过");
                continue;
            }

//            //清除上次的文件内容
//            String urlsFile = Util.replaceMacro(entry.urlsFile, envVars);
//            if (!StringUtils.isNullOrEmpty(urlsFile)) {
//                logger.println("写入下载链接文件地址 " + urlsFile);
//                File file = new File(urlsFile);
//                String absolutePath = file.getAbsolutePath();
//                logger.println("path " + absolutePath);
//                if (file.exists()) file.delete();
//            }

            //上传文件路径前缀
            //变量替换、
            String removePrefix = Util.replaceMacro(entry.removePrefix, envVars);

            //密钥配置
            Auth auth = Auth.create(profile.getAccessKey(), profile.getSecretKey());

            //第二种方式: 自动识别要上传的空间(bucket)的存储区域是华东、华北、华南。
            Zone z = Zone.autoZone();
            Configuration c = new Configuration(z);

            //创建上传对象
            UploadManager uploadManager = new UploadManager(c);

            String expanded = Util.replaceMacro(entry.source, envVars);
            FilePath[] paths = ws.list(expanded);
            for (FilePath path : paths) {
                String fullPath = path.getRemote();
//                String keyPath = path.getRemote().replace(wsPath, "");
//                String key = keyPath.replace(File.separator, "/");
                String name = path.getName();
                name=fullPath.replace(wsPath,"");
                if (!StringUtils.isNullOrEmpty(removePrefix)&& fullPath.contains(removePrefix)) {
                    name = fullPath.substring(fullPath.indexOf(removePrefix)+removePrefix.length(),fullPath.length());

                }
                logger.println("key:" + name);
                try {
                    int insertOnley = entry.noUploadOnExists ? 1 : 0;
                    //上传策略。同名文件不允许再次上传。 文件相同，名字相同，返回上传成功。文件不同，名字相同，返回上传失败提示文件已存在。
                    StringMap putPolicy = new StringMap();
                    putPolicy.put("insertOnly", insertOnley);

                    //简单上传，使用默认策略，只需要设置上传的空间名就可以了
                    String uploadToken = auth.uploadToken(entry.bucket, name, 3600, putPolicy);

                    //调用put方法上传 文件路径，上传后保存文件名，token
                    Response res = uploadManager.put(fullPath, name, uploadToken);

                    //打印返回的信息
                    String bodyString = res.bodyString();

                    //默认body返回hash和key值
                    DefaultPutRet defaultPutRet = new Gson().fromJson(bodyString, DefaultPutRet.class);
                    String hashString = defaultPutRet.hash;
                    //获得文件保存在空间中的资源名。
                    String keyString = defaultPutRet.key;

                    logger.println("上传 " + fullPath + " 到 " + entry.bucket + " 成功." + bodyString);

//                    //生成下载链接
//                    String netUrl = entry.netUrl;
//                    netUrl = netUrl + keyString;
//
//                    logger.println("下载链接　" + netUrl);
//
//                    try {
//                        if (!StringUtils.isNullOrEmpty(urlsFile)) {
//                            File urlsFile1 = new File(urlsFile);
//                            FileUtils.createOrExistsFile(urlsFile1);
//
//                            netUrl += "\n";
//                            FileUtils.writeFileFromString(urlsFile1, netUrl, true);
//                        }
//                    } catch (Exception e) {
//                        logger.println("写入链接文件失败！ " + e.getMessage());
//                    }

                } catch (QiniuException e) {
                    try {
                        logger.println("上传 " + fullPath + " 到 " + entry.bucket + " 失败 ");
                        logger.println(e.error());
                    } catch (Exception e1) {
                        //ignore
                        logger.print(e1);
                    }
                    build.setResult(Result.UNSTABLE);
                }

            }
        }
        logger.println("上传到七牛成功...");
        return true;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link cc.meetpasser.QiniuPublisher}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     * <p>
     * <p>
     * See
     * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    // This indicates to Jenkins that this is an implementation of an extension
    // point.
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {
        /**
         * To persist global configuration information, simply store it in a
         * field and call save().
         * <p>
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private final CopyOnWriteList<QiniuProfile> profiles = new CopyOnWriteList<QiniuProfile>();

        public List<QiniuProfile> getProfiles() {
            return Arrays.asList(profiles.toArray(new QiniuProfile[0]));
        }

        public QiniuProfile getProfileByName(String profileName) {
            List<QiniuProfile> profiles = this.getProfiles();
            for (QiniuProfile profile : profiles) {
//				System.console().printf(profile.getName() + "\n");
                if (profileName.equals(profile.getName())) {
                    return profile;
                }
            }
            return null;
        }

        /**
         * In order to load the persisted global configuration, you have to call
         * load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the
         * browser.
         * <p>
         * Note that returning {@link FormValidation#error(String)} does
         * not prevent the form from being saved. It just means that a
         * message will be displayed to the user.
         */
        public FormValidation doCheckAccessKey(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Access Key 不能为空");
            return FormValidation.ok();
        }

        public FormValidation doCheckProfileName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("设置项名称不能为空");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project
            // types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "上传到七牛";
        }

        @Override
        public QiniuPublisher newInstance(StaplerRequest req,
                                          JSONObject formData) throws FormException {
            List<QiniuEntry> entries = req.bindJSONToList(QiniuEntry.class,
                    formData.get("e"));
            QiniuPublisher pub = new QiniuPublisher();
            pub.getEntries().addAll(entries);
            return pub;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData)
                throws FormException {
            profiles.replaceBy(req.bindJSONToList(QiniuProfile.class,
                    formData.get("profile")));
            save();
            return true;
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    public List<QiniuEntry> getEntries() {
        return entries;
    }

}
