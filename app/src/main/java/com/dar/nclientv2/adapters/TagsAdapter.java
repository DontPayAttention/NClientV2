package com.dar.nclientv2.adapters;

import android.content.DialogInterface;
import android.support.annotation.NonNull;
import android.support.constraint.ConstraintLayout;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.util.JsonWriter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.dar.nclientv2.R;
import com.dar.nclientv2.TagFilter;
import com.dar.nclientv2.api.components.Tag;
import com.dar.nclientv2.api.enums.TagStatus;
import com.dar.nclientv2.api.enums.TagType;
import com.dar.nclientv2.async.ScrapeTags;
import com.dar.nclientv2.loginapi.LoadTags;
import com.dar.nclientv2.settings.Global;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TagsAdapter extends RecyclerView.Adapter<TagsAdapter.ViewHolder> implements Filterable{
    private final TagFilter context;
    private final List<Tag> tags;
    private List<Tag> filterTags;
    private final boolean logged;
    private String lastQuery="nothing";
    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String x=constraint.toString().toLowerCase(Locale.US);
                Log.d(Global.LOGTAG,"FILTER:\""+x+"\",\""+lastQuery+"\"="+(x.equals(lastQuery)));
                FilterResults results=new FilterResults();
                if(!x.equals(lastQuery)) {
                    results.count=filterTags.size();
                    lastQuery=x;
                    List<Tag>filterTags=new ArrayList<>();
                    for (Tag t : tags) if (t.getName().contains(x)) filterTags.add(t);
                    Log.d(Global.LOGTAG,"Size: "+filterTags.size()+filterTags);
                    results.values=filterTags;
                }else{results.count=-1;}
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                if(results.count!=-1) {
                    filterTags=(List<Tag>) results.values;
                    if(filterTags.size()>results.count)notifyItemRangeInserted(results.count,filterTags.size()-results.count);
                    else if(filterTags.size()<results.count)notifyItemRangeRemoved(filterTags.size(),results.count-filterTags.size());
                    sortDataset(true);
                }else sortDataset(false);

            }
        };
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgView;
        final TextView title,count;
        final ConstraintLayout master;
        ViewHolder(View v) {
            super(v);
            imgView = v.findViewById(R.id.image);
            title = v.findViewById(R.id.title);
            count = v.findViewById(R.id.count);
            master=v.findViewById(R.id.master_layout);
        }
    }
    private boolean orderByPopular;
    public TagsAdapter(TagFilter cont, List<Tag> tags,String query) {
        this.context=cont;
        this.tags=tags;
        online=false;
        logged=Global.isLogged();
        this.orderByPopular=!Global.isTagOrderByPopular();
        filterTags=new ArrayList<>();
        getFilter().filter(query);
    }
    private final boolean online;
    public TagsAdapter(TagFilter cont,String query){
        this.context=cont;
        online=true;
        logged=Global.isLogged();
        if(Global.getOnlineTags().size()==0){
            this.tags=new ArrayList<>();
            new LoadTags(this).start();
        }else this.tags=new ArrayList<>(Global.getOnlineTags());
        this.orderByPopular=!Global.isTagOrderByPopular();
        filterTags=new ArrayList<>();
        getFilter().filter(query);
    }

    @NonNull
    @Override
    public TagsAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TagsAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_tag_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final TagsAdapter.ViewHolder holder, int position) {
        final Tag ent=filterTags.get(holder.getAdapterPosition());
        holder.title.setText(ent.getName());
        holder.count.setText(String.format(Locale.US,"%d",ent.getCount()));
        holder.master.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!online) {
                    if (!Global.maxTagReached() || Global.getStatus(ent) != TagStatus.DEFAULT) updateLogo(holder.imgView, Global.updateStatus(context, ent));
                    else Snackbar.make(context.getViewPager(), context.getString(R.string.tags_max_reached, Global.MAXTAGS), Snackbar.LENGTH_LONG).show();
                }else{
                    try {
                        onlineTagUpdate(ent,!Global.getOnlineTags().contains(ent),holder.imgView);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        if(!online&&logged)holder.master.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if(!Global.getOnlineTags().contains(ent)) showBlacklistDialog(ent,holder.imgView);
                else Toast.makeText(context, R.string.tag_already_in_blacklist, Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        updateLogo(holder.imgView,online?TagStatus.AVOIDED:Global.getStatus(ent));
    }
    private void showBlacklistDialog(final Tag tag,final ImageView imgView) {
        AlertDialog.Builder builder=new AlertDialog.Builder(context);
        builder.setIcon(R.drawable.ic_star_border).setTitle(R.string.add_to_online_blacklist).setMessage(R.string.are_you_sure);
        builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                try {
                    onlineTagUpdate(tag,true,imgView);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).setNegativeButton(R.string.no,null).show();
    }
    private void onlineTagUpdate(final Tag tag, final boolean add,final ImageView imgView) throws IOException{
        StringWriter sw=new StringWriter();
        JsonWriter jw=new JsonWriter(sw);
        jw.beginObject().name("added").beginArray();
        if(add)writeTag(jw,tag);
        jw.endArray().name("removed").beginArray();
        if(!add)writeTag(jw,tag);
        jw.endArray().endObject();
        final String url=String.format(Locale.US,"https://nhentai.net/users/%s/%s/blacklist",Global.getUser().getId(),Global.getUser().getCodename());
        final RequestBody ss=RequestBody.create(MediaType.get("application/json"),sw.toString());
        Global.client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {}

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String token=response.body().string();
                token=token.substring(token.lastIndexOf("csrf_token"));
                token=token.substring(token.indexOf('"')+1);
                token=token.substring(0,token.indexOf('"'));
                Global.client.newCall(new Request.Builder().addHeader("Referer",url).addHeader("X-CSRFToken",token).url(url).post(ss).build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {}

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String s=response.body().string();
                        Log.d(Global.LOGTAG,"Response: "+s);
                        if(s.equals("{\"status\": \"ok\"}")) {
                            if (add) Global.addOnlineTag(tag);
                            else Global.removeOnlineTag(tag);
                            if(online)updateLogo(imgView, add ? TagStatus.AVOIDED : TagStatus.DEFAULT);
                        }
                    }
                });
            }
        });
    }
    private static void writeTag(JsonWriter jw, Tag tag) throws IOException{
        jw.beginObject();
        jw.name("id").value(tag.getId());
        jw.name("name").value(tag.getName());
        jw.name("type").value(tag.getTypeString());
        jw.endObject();
    }
    private static void updateLogo(ImageView img, TagStatus s){
        switch (s){
            case DEFAULT:img.setImageResource(R.drawable.ic_void);break;
            case ACCEPTED:img.setImageResource(R.drawable.ic_check);break;
            case AVOIDED:img.setImageResource(R.drawable.ic_close);break;
        }
        Global.setTint(img.getDrawable());
    }
    @Override
    public int getItemCount() {
        return filterTags.size();
    }

    public List<Tag> getDataset() {
        return filterTags;
    }
    public List<Tag> getTrueDataset() {
        return tags;
    }
    public void sortDataset(boolean force){
        boolean byPopular=Global.isTagOrderByPopular();
        Log.d(Global.LOGTAG,"SORT: "+(byPopular==orderByPopular));
        if(byPopular==orderByPopular&&!force)return;
        orderByPopular=byPopular;
        if(orderByPopular) Collections.sort(filterTags, new Comparator<Tag>() {
            @Override
            public int compare(Tag o1, Tag o2) {
                return o2.getCount()-o1.getCount();
            }
        });
        else Collections.sort(filterTags, new Comparator<Tag>() {
            @Override
            public int compare(Tag o1, Tag o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        notifyItemRangeChanged(0,filterTags.size());
    }
    public void addItem(Tag tag){
        tags.add(tag);
        if(tag.getName().contains(lastQuery)){
            filterTags.add(tag);
            context.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    notifyItemInserted(filterTags.size());
                    //notifyDataSetChanged();
                }
            });
        }
    }
    public void resetDataset(TagType type){
        tags.clear();
        int s=filterTags.size();
        filterTags=new ArrayList<>();
        notifyItemRangeRemoved(0,s);
        if(!online)new ScrapeTags(context,this,type).start();
        else new LoadTags(this).start();
    }

}